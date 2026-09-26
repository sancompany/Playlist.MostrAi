package br.com.mostrai.player.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.config.ConfigRemota
import br.com.mostrai.player.config.ConfigRemotaJson
import br.com.mostrai.player.config.MargensOverscan
import br.com.mostrai.player.estado.DiarioBordo
import br.com.mostrai.player.estado.EstadoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Parte 4, 5, 6, 7 e 13: contrato V2 com degradação automática para V1. */
@RunWith(RobolectricTestRunner::class)
class SincronizacaoV2Test {

    private class ApiFalsa(config: ConfigAparelho) : MostraiApi(config) {
        var respostaHeartbeat: ResultadoHttp<HeartbeatJson.Resposta> =
            ResultadoHttp.Ok(HeartbeatJson.Resposta())
        var respostaHello: ResultadoHttp<Int?> = ResultadoHttp.Ok(null)
        var respostaConfig: ResultadoHttp<Pair<ConfigRemota, String>> =
            ResultadoHttp.NaoEncontrado
        var respostaProvisionar: ResultadoHttp<Credenciais> = ResultadoHttp.NaoEncontrado

        var helloChamados = 0
        var configChamados = 0

        override fun heartbeat(corpo: HeartbeatJson.Corpo) = respostaHeartbeat

        override fun hello(dados: HelloJson.Dados): ResultadoHttp<Int?> {
            helloChamados++
            return respostaHello
        }

        override fun buscarConfig(): ResultadoHttp<Pair<ConfigRemota, String>> {
            configChamados++
            return respostaConfig
        }

        override fun provisionar(token: String) = respostaProvisionar
    }

    private lateinit var contexto: Context
    private lateinit var config: ConfigAparelho
    private lateinit var api: ApiFalsa
    private lateinit var diario: DiarioBordo
    private lateinit var sync: SincronizacaoV2

    private val corpo = HeartbeatJson.Corpo(
        estado = EstadoPlayer.PLAYING,
        configVersionAplicada = 0,
        criativoId = null,
        ultimaPlaylistOkEm = null,
        filaPendentes = 0,
        filaMaisAntigoEm = null,
        erroCodigo = null,
        erroEm = null,
        erroMensagem = null,
        desvioRelogioMs = null,
    )

    private val dadosHello = HelloJson.Dados(
        versaoApp = "1.0.0",
        buildNumber = 2,
        fabricante = "TCL",
        modelo = "32S6500S",
        android = "8.0.0",
        largura = 1920,
        altura = 1080,
        timezone = "America/Sao_Paulo",
    )

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        contexto.getSharedPreferences("mostrai_config", Context.MODE_PRIVATE).edit().clear().commit()
        contexto.deleteDatabase(DiarioBordo.NOME_ARQUIVO)

        config = ConfigAparelho(contexto)
        config.baseUrl = "https://exemplo.com/api"
        config.dispositivoId = "tela-1"
        config.chaveAparelho = "chave"

        api = ApiFalsa(config)
        diario = DiarioBordo(contexto)
        sync = SincronizacaoV2(config, api, diario)
    }

    // ------------------------------------------- compatibilidade V1 (Regra)

    @Test
    fun `404 no heartbeat marca backend V1 e nao quebra nada`() {
        // Parte de V2 disponível: o default já é false, e sem isto o teste
        // passava mesmo com a marcação removida (mutação M10, Ciclo 18).
        config.backendV2Disponivel = true
        api.respostaHeartbeat = ResultadoHttp.NaoEncontrado

        val efeitos = sync.heartbeat(corpo)

        assertFalse(config.backendV2Disponivel)
        assertFalse(efeitos.atualizarPlaylist)
        assertFalse(efeitos.autenticacaoFalhou)
    }

    @Test
    fun `404 no hello nao impede o resto do ciclo`() {
        api.respostaHello = ResultadoHttp.NaoEncontrado

        sync.helloSeNecessario(dadosHello)

        assertFalse(config.backendV2Disponivel)
        assertNull(config.assinaturaHello)
    }

    @Test
    fun `resposta V1 com margens continua aplicando margem`() {
        api.respostaHeartbeat = ResultadoHttp.Ok(
            HeartbeatJson.Resposta(margens = MargensOverscan(1f, 2f, 3f, 4f))
        )

        val efeitos = sync.heartbeat(corpo)

        assertEquals(MargensOverscan(1f, 2f, 3f, 4f), efeitos.margens)
    }

    // ------------------------------------------------------- Parte 4: hello

    @Test
    fun `hello vai uma vez e nao repete com os mesmos dados`() {
        sync.helloSeNecessario(dadosHello)
        sync.helloSeNecessario(dadosHello)

        assertEquals(1, api.helloChamados)
        assertEquals(dadosHello.assinatura(), config.assinaturaHello)
    }

    @Test
    fun `hello e reenviado quando algo tecnico muda`() {
        sync.helloSeNecessario(dadosHello)
        sync.helloSeNecessario(dadosHello.copy(versaoApp = "1.1.0"))

        assertEquals(2, api.helloChamados)
    }

    @Test
    fun `hello sem credencial nem tenta`() {
        config.chaveAparelho = null

        sync.helloSeNecessario(dadosHello)

        assertEquals(0, api.helloChamados)
    }

    // ------------------------------------------------------ Parte 7: config

    @Test
    fun `versao igual nao busca config`() {
        config.configVersionAplicada = 184
        api.respostaHeartbeat = ResultadoHttp.Ok(HeartbeatJson.Resposta(configVersion = 184))

        sync.heartbeat(corpo)

        assertEquals(0, api.configChamados)
    }

    @Test
    fun `versao divergente busca e aplica`() {
        val corpoConfig = """{"configVersion": 184, "rotacaoTela": 90}"""
        api.respostaConfig = ResultadoHttp.Ok(ConfigRemotaJson.parse(corpoConfig)!! to corpoConfig)
        api.respostaHeartbeat = ResultadoHttp.Ok(HeartbeatJson.Resposta(configVersion = 184))

        sync.heartbeat(corpo)

        assertEquals(1, api.configChamados)
        assertEquals(184, config.configVersionAplicada)
        assertEquals(90, config.rotacaoTela)
    }

    @Test
    fun `config que falha preserva o ultimo valido`() {
        val corpoConfig = """{"configVersion": 10, "rotacaoTela": 180}"""
        api.respostaConfig = ResultadoHttp.Ok(ConfigRemotaJson.parse(corpoConfig)!! to corpoConfig)
        sync.sincronizarConfigSeNecessario(10)

        api.respostaConfig = ResultadoHttp.ErroServidor(500)
        sync.sincronizarConfigSeNecessario(11)

        // Nada mudou: a tela continua com a config boa, e o admin verá
        // "pendente" até o próximo ciclo — que é o comportamento certo.
        assertEquals(10, config.configVersionAplicada)
        assertEquals(180, config.rotacaoTela)
    }

    @Test
    fun `config aplicada fica gravada e sobrevive a releitura`() {
        val corpoConfig = """
            {"configVersion": 5, "operacao": {"regime": "CUSTOM", "timezone": "America/Bahia"}}
        """.trimIndent()
        api.respostaConfig = ResultadoHttp.Ok(ConfigRemotaJson.parse(corpoConfig)!! to corpoConfig)

        sync.sincronizarConfigSeNecessario(5)

        assertEquals("America/Bahia", ConfigAparelho(contexto).horarioOperacional().timezone)
    }

    @Test
    fun `falha de config vira erro duravel no diario`() {
        api.respostaConfig = ResultadoHttp.ErroServidor(500)

        sync.sincronizarConfigSeNecessario(9)

        assertEquals("CONFIG_FALHOU", diario.ultimoErro()?.codigo)
    }

    // ---------------------------------------------------- Parte 6: efeitos

    @Test
    fun `playlist atualizar chega como efeito`() {
        api.respostaHeartbeat = ResultadoHttp.Ok(HeartbeatJson.Resposta(atualizarPlaylist = true))

        assertTrue(sync.heartbeat(corpo).atualizarPlaylist)
    }

    @Test
    fun `nova chave vira candidata, nunca sobrescreve direto`() {
        api.respostaHeartbeat = ResultadoHttp.Ok(HeartbeatJson.Resposta(novaChave = "chave-nova"))

        sync.heartbeat(corpo)

        assertEquals("chave", config.chaveAparelho)
        assertEquals("chave-nova", config.chaveCandidata)
    }

    @Test
    fun `nova chave igual a atual e ignorada`() {
        api.respostaHeartbeat = ResultadoHttp.Ok(HeartbeatJson.Resposta(novaChave = "chave"))

        sync.heartbeat(corpo)

        assertNull(config.chaveCandidata)
    }

    @Test
    fun `401 marca falha de autenticacao e registra no diario`() {
        api.respostaHeartbeat = ResultadoHttp.ErroAutenticacao(401)

        val efeitos = sync.heartbeat(corpo)

        assertTrue(efeitos.autenticacaoFalhou)
        assertEquals("AUTH_FALHOU", diario.ultimoErro()?.codigo)
    }

    @Test
    fun `erro de rede nao suja o diario`() {
        api.respostaHeartbeat = ResultadoHttp.SemRede("timeout")

        sync.heartbeat(corpo)

        // Rede caindo numa loja é rotina, não um caso a investigar — o diário
        // existe para o que o operador precisa ver.
        assertNull(diario.ultimoErro())
    }

    // ------------------------------------------- Parte 13: token single-use

    @Test
    fun `token vira credencial e some`() {
        config.dispositivoId = null
        config.chaveAparelho = null
        config.tokenProvisionamento = "token-abc"
        api.respostaProvisionar = ResultadoHttp.Ok(MostraiApi.Credenciais("tela-9", "chave-9"))

        assertTrue(sync.provisionarSeNecessario())

        assertEquals("tela-9", config.dispositivoId)
        assertEquals("chave-9", config.chaveAparelho)
        assertNull(config.tokenProvisionamento)
    }

    @Test
    fun `token sobrevive quando o endpoint ainda nao existe`() {
        config.dispositivoId = null
        config.chaveAparelho = null
        config.tokenProvisionamento = "token-abc"
        api.respostaProvisionar = ResultadoHttp.NaoEncontrado

        assertFalse(sync.provisionarSeNecessario())

        // Jogar o token fora aqui transformaria "backend ainda não atualizado"
        // numa TV que precisa de visita.
        assertEquals("token-abc", config.tokenProvisionamento)
    }

    @Test
    fun `token sobrevive a falha de rede`() {
        config.dispositivoId = null
        config.chaveAparelho = null
        config.tokenProvisionamento = "token-abc"
        api.respostaProvisionar = ResultadoHttp.SemRede("timeout")

        assertFalse(sync.provisionarSeNecessario())

        assertEquals("token-abc", config.tokenProvisionamento)
    }

    @Test
    fun `aparelho ja provisionado ignora token`() {
        config.tokenProvisionamento = "token-abc"

        assertFalse(sync.provisionarSeNecessario())
        assertNotNull(config.tokenProvisionamento)
    }

}
