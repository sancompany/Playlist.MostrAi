package br.com.mostrai.player.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.BuildConfig
import br.com.mostrai.player.HostDaApi
import br.com.mostrai.player.Produto
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.estado.EstadoPlayer
import br.com.mostrai.player.proof.EventoExibicao
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** As 5 rotas, pelo fio, contra um servidor local (contrato §3–§8). */
@RunWith(RobolectricTestRunner::class)
class MostraiApiContratoTest {

    private lateinit var servidor: ServidorDeTeste
    private lateinit var config: ConfigAparelho
    private lateinit var api: MostraiApi
    private val recusadas = mutableListOf<String>()

    @Before
    fun preparar() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        contexto.getSharedPreferences(ConfigAparelho.ARQUIVO, Context.MODE_PRIVATE).edit().clear().commit()
        servidor = ServidorDeTeste()
        config = ConfigAparelho(contexto)
        api = MostraiApi(config, base = { servidor.baseUrl })
        api.aoRecusarCredencial = { recusadas += it }
    }

    @After
    fun encerrar() = servidor.encerrar()

    private fun provisionado() = config.gravarCredenciais("M-0235", "chave-secreta")

    private val heartbeat = HeartbeatJson.Corpo(EstadoPlayer.PLAYING, 7, "88", null, 0, null)

    // ------------------------------------------------------------- servidor

    @Test
    fun `servidor de producao e fixo no APK`() {
        assertEquals("https://mostrai.sancocore.com.br", Produto.BASE_URL)
        assertTrue(Produto.BASE_URL.startsWith("https://"))
        assertFalse(Produto.BASE_URL.endsWith("/"))
        assertEquals(Produto.BASE_URL, HostDaApi.base)
    }

    // --------------------------------------------------------- provisionar

    @Test
    fun `provisionar manda so ID e codigo, sem credencial`() {
        servidor.rotas["/player/provisionar"] =
            ServidorDeTeste.Resposta(200, """{"dispositivoId":"M-0235","chaveAparelho":"${"x".repeat(43)}"}""")

        val resposta = api.provisionar("M-0235", "7K4M-9Q2W")

        assertEquals(MostraiApi.Provisionamento.Ok(MostraiApi.Credenciais("M-0235", "x".repeat(43))), resposta)
        val req = servidor.ultima("/player/provisionar")!!
        assertEquals("POST", req.metodo)
        val corpo = JSONObject(req.corpo)
        assertEquals(setOf("codigoTela", "codigoInstalacao"), corpo.keys().asSequence().toSet())
        assertEquals("M-0235", corpo.getString("codigoTela"))
        assertEquals("7K4M-9Q2W", corpo.getString("codigoInstalacao"))
        assertNull(req.cabecalhos["x-aparelho-key"])
        assertEquals("${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}", req.cabecalhos["x-player-version"])
        assertTrue(req.cabecalhos["content-type"]!!.startsWith("application/json"))
    }

    @Test
    fun `provisionar classifica 400, 401, 429 e 5xx`() {
        fun com(codigo: Int, cabecalhos: Map<String, String> = emptyMap()): MostraiApi.Provisionamento {
            servidor.rotas["/player/provisionar"] = ServidorDeTeste.Resposta(codigo, """{"erro":"x"}""".toByteArray(), null, cabecalhos)
            return api.provisionar("M-0235", "7K4M-9Q2W")
        }
        assertEquals(MostraiApi.Provisionamento.Invalido, com(400))
        assertEquals(MostraiApi.Provisionamento.Recusado, com(401))
        assertEquals(MostraiApi.Provisionamento.Limitado(30), com(429, mapOf("Retry-After" to "30")))
        assertTrue(com(503) is MostraiApi.Provisionamento.Transitorio)
        assertTrue("401 do provisionamento não é revogação", recusadas.isEmpty())
    }

    @Test
    fun `200 sem a chave nao conta como provisionado`() {
        servidor.rotas["/player/provisionar"] = ServidorDeTeste.Resposta(200, """{"dispositivoId":"M-0235"}""")
        assertTrue(api.provisionar("M-0235", "7K4M-9Q2W") is MostraiApi.Provisionamento.Transitorio)
    }

    // ------------------------------------------------------------ autenticadas

    @Test
    fun `rotas autenticadas levam X-Aparelho-Key e o ID no caminho`() {
        provisionado()
        servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, """{"janelaId":"j","itens":[]}""")
        servidor.rotas["/player/"] = ServidorDeTeste.Resposta(200, """{"configVersion":1}""")

        api.buscarPlaylist()
        api.heartbeat(heartbeat)
        api.buscarConfig()
        api.enviarLote(emptyList())

        assertEquals(
            listOf(
                "GET /playlist/M-0235", "POST /player/M-0235/heartbeat",
                "GET /player/M-0235/config", "POST /player/M-0235/played",
            ),
            servidor.recebidas.toList(),
        )
        servidor.detalhadas.forEach {
            assertEquals(it.caminho, "chave-secreta", it.cabecalhos["x-aparelho-key"])
            assertNull("header do contrato antigo", it.cabecalhos["x-aparelho-id"])
            assertNull("header do contrato antigo", it.cabecalhos["x-player-contract"])
            assertEquals("${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}", it.cabecalhos["x-player-version"])
        }
    }

    @Test
    fun `sem credencial nenhuma requisicao autenticada sai`() {
        assertEquals(ResultadoHttp.SemCredencial, api.heartbeat(heartbeat))
        assertEquals(ResultadoHttp.SemCredencial, api.buscarPlaylist())
        assertTrue(servidor.recebidas.isEmpty())
    }

    @Test
    fun `401 avisa a revogacao com a chave que o levou`() {
        provisionado()
        servidor.rotas["/player/"] = ServidorDeTeste.Resposta(401, """{"erro":"aparelho não autorizado"}""")

        assertTrue(api.heartbeat(heartbeat) is ResultadoHttp.CredencialRecusada)
        assertEquals(listOf("chave-secreta"), recusadas)
    }

    @Test
    fun `403 e tela suspensa, nao revogacao`() {
        provisionado()
        servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(403, """{"erro":"fora do ar"}""")

        assertEquals(ResultadoHttp.TelaSuspensa, api.buscarPlaylist())
        assertTrue(recusadas.isEmpty())
    }

    @Test
    fun `5xx, 429 e corpo fora do contrato nao sao revogacao`() {
        provisionado()
        servidor.rotas["/player/"] = ServidorDeTeste.Resposta(503, "")
        assertTrue(api.heartbeat(heartbeat) is ResultadoHttp.ErroServidor)
        servidor.rotas["/player/"] = ServidorDeTeste.Resposta(429, ByteArray(0), null, mapOf("Retry-After" to "5"))
        assertEquals(ResultadoHttp.Limitado(5), api.heartbeat(heartbeat))
        servidor.rotas["/player/"] = ServidorDeTeste.Resposta(200, "<html>proxy</html>")
        assertTrue(api.heartbeat(heartbeat) is ResultadoHttp.RespostaInvalida)
        assertTrue(recusadas.isEmpty())
    }

    @Test
    fun `sem servidor e falta de rede, nunca excecao`() {
        provisionado()
        val semServidor = MostraiApi(config, base = { "http://127.0.0.1:1" })
        assertTrue(semServidor.buscarPlaylist() is ResultadoHttp.SemRede)
        assertTrue(semServidor.provisionar("M-0235", "7K4M-9Q2W") is MostraiApi.Provisionamento.Transitorio)
    }

    @Test
    fun `played manda o lote e le os resultados`() {
        provisionado()
        servidor.rotas["/player/"] =
            ServidorDeTeste.Resposta(200, """{"resultados":[{"execucaoId":"e1","status":"contabilizado"}]}""")
        val evento = EventoExibicao("e1", "j", "i", "c", "2026-09-26T10:00:00-03:00", "2026-09-26T10:00:15-03:00", 0, 0L, 0L)

        val resposta = api.enviarLote(listOf(evento))

        assertEquals(ResultadoHttp.Ok(mapOf("e1" to "contabilizado")), resposta)
        assertEquals("e1", JSONObject(servidor.ultima("/player/")!!.corpo).getJSONArray("eventos").getJSONObject(0).getString("execucaoId"))
    }

    @Test
    fun `413 no lote e tratado como lote recusado`() {
        provisionado()
        servidor.rotas["/player/"] = ServidorDeTeste.Resposta(413, "")
        assertEquals(ResultadoHttp.RespostaInvalida("HTTP 413"), api.enviarLote(emptyList()))
    }
}
