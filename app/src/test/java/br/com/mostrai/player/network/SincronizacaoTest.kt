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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Heartbeat → config (contrato §5 e §6). */
@RunWith(RobolectricTestRunner::class)
class SincronizacaoTest {

    private class ApiFalsa(config: ConfigAparelho) : MostraiApi(config) {
        var respostaHeartbeat: ResultadoHttp<HeartbeatJson.Resposta> = ResultadoHttp.Ok(HeartbeatJson.Resposta())
        var respostaConfig: ResultadoHttp<Pair<ConfigRemota, String>> = ResultadoHttp.SemRede("x")
        var buscasDeConfig = 0

        override fun heartbeat(corpo: HeartbeatJson.Corpo) = respostaHeartbeat
        override fun buscarConfig(): ResultadoHttp<Pair<ConfigRemota, String>> {
            buscasDeConfig++
            return respostaConfig
        }
    }

    private lateinit var config: ConfigAparelho
    private lateinit var api: ApiFalsa
    private lateinit var diario: DiarioBordo
    private lateinit var sync: Sincronizacao

    private val corpo = HeartbeatJson.Corpo(EstadoPlayer.PLAYING, 0, null, null, 0, null)

    private fun config(texto: String): ResultadoHttp<Pair<ConfigRemota, String>> =
        ResultadoHttp.Ok(ConfigRemotaJson.parse(texto)!! to texto)

    @Before
    fun preparar() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        contexto.getSharedPreferences(ConfigAparelho.ARQUIVO, Context.MODE_PRIVATE).edit().clear().commit()
        contexto.deleteDatabase(DiarioBordo.NOME_ARQUIVO)
        config = ConfigAparelho(contexto).apply { gravarCredenciais("M-0001", "chave") }
        api = ApiFalsa(config)
        diario = DiarioBordo(contexto)
        sync = Sincronizacao(config, api, diario)
    }

    @Test
    fun `mesma versao de config nao busca nada`() {
        api.respostaHeartbeat = ResultadoHttp.Ok(HeartbeatJson.Resposta(configVersion = 0))

        val efeitos = sync.heartbeat(corpo)

        assertEquals(0, api.buscasDeConfig)
        assertFalse(efeitos.configAplicada)
        assertTrue(efeitos.alcancouServidor)
    }

    @Test
    fun `versao nova busca, aplica e so entao grava a versao`() {
        api.respostaHeartbeat = ResultadoHttp.Ok(HeartbeatJson.Resposta(configVersion = 7))
        api.respostaConfig = config("""{"configVersion":7,"margens":{"superior":2},"pinSaida":"4821"}""")

        val efeitos = sync.heartbeat(corpo)

        assertTrue(efeitos.configAplicada)
        assertEquals(7, config.configVersionAplicada)
        assertEquals(MargensOverscan(topo = 2f), config.margens)
        assertEquals("4821", config.pinSaida)
    }

    @Test
    fun `config que falha deixa a anterior intacta e tenta no proximo heartbeat`() {
        api.respostaHeartbeat = ResultadoHttp.Ok(HeartbeatJson.Resposta(configVersion = 3))
        api.respostaConfig = config("""{"configVersion":3,"margens":{"superior":1}}""")
        sync.heartbeat(corpo)

        api.respostaHeartbeat = ResultadoHttp.Ok(HeartbeatJson.Resposta(configVersion = 4))
        api.respostaConfig = ResultadoHttp.ErroServidor(502)
        assertFalse(sync.heartbeat(corpo).configAplicada)
        assertEquals(3, config.configVersionAplicada)
        assertEquals(1f, config.margens.topo)

        api.respostaConfig = config("""{"configVersion":4,"margens":{"superior":5}}""")
        assertTrue(sync.heartbeat(corpo).configAplicada)
        assertEquals(4, config.configVersionAplicada)
    }

    @Test
    fun `playlist atualizar chega como efeito`() {
        api.respostaHeartbeat = ResultadoHttp.Ok(HeartbeatJson.Resposta(atualizarPlaylist = true))
        assertTrue(sync.heartbeat(corpo).atualizarPlaylist)
    }

    @Test
    fun `rede caida nao pede nada e nao suja o diario`() {
        api.respostaHeartbeat = ResultadoHttp.SemRede("timeout")

        val efeitos = sync.heartbeat(corpo)

        assertFalse(efeitos.alcancouServidor)
        assertFalse(efeitos.atualizarPlaylist)
        assertEquals(null, diario.ultimoErro())
    }

    @Test
    fun `401 no heartbeat nao aplica nada`() {
        api.respostaHeartbeat = ResultadoHttp.CredencialRecusada(401)
        val efeitos = sync.heartbeat(corpo)
        assertFalse(efeitos.configAplicada)
        assertFalse(efeitos.alcancouServidor)
    }
}
