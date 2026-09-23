package br.com.mostrai.player.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.estado.EstadoPlayer
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Autenticação, headers e classificação de HTTP (R7, R8, Parte 3, Parte 14).
 */
@RunWith(RobolectricTestRunner::class)
class MostraiApiAutenticacaoTest {

    /** Transporte de mentira: guarda o que foi pedido, devolve o que mandarem. */
    private class HttpFalso : HttpCliente() {
        var proximaResposta: Resposta = Resposta(200, "{}", emptyMap())
        var lancar: IOException? = null
        var ultimosCabecalhos: Map<String, String> = emptyMap()
        var chamadas = 0

        override fun get(url: String, cabecalhos: Map<String, String>): Resposta = registrar(cabecalhos)

        override fun post(url: String, cabecalhos: Map<String, String>, corpo: String): Resposta =
            registrar(cabecalhos)

        private fun registrar(cabecalhos: Map<String, String>): Resposta {
            chamadas++
            ultimosCabecalhos = cabecalhos
            lancar?.let { throw it }
            return proximaResposta
        }
    }

    private lateinit var config: ConfigAparelho
    private lateinit var http: HttpFalso
    private lateinit var api: MostraiApi

    private val corpoHeartbeat = HeartbeatJson.Corpo(
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
        updateEstado = null,
    )

    @Before
    fun preparar() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        contexto.getSharedPreferences("mostrai_config", Context.MODE_PRIVATE).edit().clear().commit()
        config = ConfigAparelho(contexto)
        config.baseUrl = "https://exemplo.com/api"
        config.dispositivoId = "tela-1"
        config.chaveAparelho = "chave-boa"
        http = HttpFalso()
        api = MostraiApi(config, http)
    }

    // ------------------------------------------------------------------- R7

    @Test
    fun `sem chave nao faz requisicao nenhuma`() {
        config.chaveAparelho = null

        val resultado = api.heartbeat(corpoHeartbeat)

        assertTrue(resultado is ResultadoHttp.SemCredencial)
        assertEquals(0, http.chamadas)
    }

    @Test
    fun `chave em branco tambem nao faz requisicao`() {
        config.chaveAparelho = "   "

        assertTrue(api.heartbeat(corpoHeartbeat) is ResultadoHttp.SemCredencial)
        assertEquals(0, http.chamadas)
    }

    @Test
    fun `playlist sem credencial nao vira requisicao com header vazio`() {
        config.chaveAparelho = ""

        val resposta = api.buscarPlaylist()

        assertTrue(resposta is MostraiApi.RespostaPlaylist.ErroAparelho)
        assertEquals(0, http.chamadas)
    }

    // -------------------------------------------------------- Parte 3: headers

    @Test
    fun `toda chamada leva versao e contrato do player`() {
        api.heartbeat(corpoHeartbeat)

        assertTrue(http.ultimosCabecalhos.containsKey("X-Player-Version"))
        assertEquals("2", http.ultimosCabecalhos["X-Player-Contract"])
    }

    @Test
    fun `chave viaja nos dois nomes de header durante a migracao`() {
        api.heartbeat(corpoHeartbeat)

        assertEquals("chave-boa", http.ultimosCabecalhos["X-Aparelho-Id"])
        assertEquals("chave-boa", http.ultimosCabecalhos["X-Aparelho-Key"])
    }

    // ------------------------------------------------------------------- R8

    @Test
    fun `401 e 403 viram erro de autenticacao, nao erro generico`() {
        http.proximaResposta = HttpCliente.Resposta(401, "", emptyMap())
        assertTrue(api.heartbeat(corpoHeartbeat) is ResultadoHttp.ErroAutenticacao)

        http.proximaResposta = HttpCliente.Resposta(403, "", emptyMap())
        assertTrue(api.heartbeat(corpoHeartbeat) is ResultadoHttp.ErroAutenticacao)
    }

    @Test
    fun `404 vira NaoEncontrado, que e como um backend V1 responde a rota V2`() {
        http.proximaResposta = HttpCliente.Resposta(404, "", emptyMap())

        assertTrue(api.heartbeat(corpoHeartbeat) is ResultadoHttp.NaoEncontrado)
    }

    @Test
    fun `429 preserva o Retry-After`() {
        http.proximaResposta = HttpCliente.Resposta(429, "", mapOf("Retry-After" to listOf("120")))

        val resultado = api.heartbeat(corpoHeartbeat)

        assertEquals(120, (resultado as ResultadoHttp.Limitado).segundos)
    }

    @Test
    fun `5xx vira erro de servidor`() {
        http.proximaResposta = HttpCliente.Resposta(503, "", emptyMap())

        val resultado = api.heartbeat(corpoHeartbeat)

        assertEquals(503, (resultado as ResultadoHttp.ErroServidor).codigo)
    }

    @Test
    fun `falha de rede vira SemRede, distinta de erro de servidor`() {
        http.lancar = IOException("timeout")

        assertTrue(api.heartbeat(corpoHeartbeat) is ResultadoHttp.SemRede)
    }

    @Test
    fun `codigoDiagnostico distingue as familias`() {
        assertEquals("AUTH_401", ResultadoHttp.ErroAutenticacao(401).codigoDiagnostico())
        assertEquals("SERVIDOR_500", ResultadoHttp.ErroServidor(500).codigoDiagnostico())
        assertEquals("SEM_REDE", ResultadoHttp.SemRede("x").codigoDiagnostico())
        assertEquals("NAO_ENCONTRADO", ResultadoHttp.NaoEncontrado.codigoDiagnostico())
    }

    // ------------------------------------------------- Parte 14: rotação segura

    @Test
    fun `candidata e usada na requisicao antes de ser promovida`() {
        config.chaveCandidata = "chave-nova"

        api.heartbeat(corpoHeartbeat)

        assertEquals("chave-nova", http.ultimosCabecalhos["X-Aparelho-Key"])
    }

    @Test
    fun `candidata so vira oficial depois de uma resposta boa`() {
        config.chaveCandidata = "chave-nova"

        api.heartbeat(corpoHeartbeat)

        assertEquals("chave-nova", config.chaveAparelho)
        assertNull(config.chaveCandidata)
    }

    @Test
    fun `candidata recusada nao destroi a chave que funcionava`() {
        config.chaveCandidata = "chave-ruim"
        http.proximaResposta = HttpCliente.Resposta(401, "", emptyMap())

        api.heartbeat(corpoHeartbeat)

        assertEquals("chave-boa", config.chaveAparelho)
        assertNull(config.chaveCandidata)
    }

    @Test
    fun `falha de rede no meio da rotacao nao promove nem descarta`() {
        config.chaveCandidata = "chave-nova"
        http.lancar = IOException("sem rede")

        api.heartbeat(corpoHeartbeat)

        assertEquals("chave-boa", config.chaveAparelho)
        assertEquals("chave-nova", config.chaveCandidata)
    }

    // ------------------------------------------------------- provisionamento

    @Test
    fun `provisionar troca token por credenciais`() {
        http.proximaResposta = HttpCliente.Resposta(
            200,
            """{"dispositivoId": "tela-9", "chaveAparelho": "chave-9"}""",
            emptyMap(),
        )

        val resultado = api.provisionar("token-abc")

        val credenciais = (resultado as ResultadoHttp.Ok).valor
        assertEquals("tela-9", credenciais.dispositivoId)
        assertEquals("chave-9", credenciais.chaveAparelho)
    }

    @Test
    fun `provisionar com resposta incompleta e invalida`() {
        http.proximaResposta = HttpCliente.Resposta(200, """{"dispositivoId": "tela-9"}""", emptyMap())

        assertTrue(api.provisionar("token-abc") is ResultadoHttp.RespostaInvalida)
    }

    @Test
    fun `provisionar em backend sem a rota devolve NaoEncontrado`() {
        http.proximaResposta = HttpCliente.Resposta(404, "", emptyMap())

        assertTrue(api.provisionar("token-abc") is ResultadoHttp.NaoEncontrado)
    }
}
