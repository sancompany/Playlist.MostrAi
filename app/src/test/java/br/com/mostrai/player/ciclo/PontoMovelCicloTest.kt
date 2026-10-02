package br.com.mostrai.player.ciclo

import android.content.Context
import android.provider.Settings
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.estado.EstadoPlayer
import br.com.mostrai.player.operacao.RegistroOperacional
import br.com.mostrai.player.playlist.PlaylistCache
import br.com.mostrai.player.playlist.RelogioConfiavel
import br.com.mostrai.player.playlist.RelogioJanela
import br.com.mostrai.player.proof.ProofOfPlayDb
import java.time.Instant
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/**
 * Ponto Móvel (02/10/2026): a tela sai preparada da base, fica dias sem
 * internet, reinicia, e volta a sincronizar — sem inventar veiculação, sem
 * perder comprovante nem tempo operacional.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PontoMovelCicloTest {

    private lateinit var h: Harness
    private val hora = 3_600_000L

    @Before
    fun preparar() {
        h = Harness()
        h.provisionar()
        h.midiaNoAr()
    }

    @After
    fun encerrar() = h.encerrar()

    private fun estado(atividade: br.com.mostrai.player.PlayerActivity) =
        h.campo<EstadoPlayer>(atividade, "estadoAtual")

    /** Playlist com um comercial e um institucional, janela [inicio, inicio+1h), relógio do servidor em [agora]. */
    private fun playlist(inicioMs: Long, agoraMs: Long): String {
        val inicio = Instant.ofEpochMilli(inicioMs)
        val base = h.servidor.baseUrl
        return """
            {"versaoContrato":2,"janelaId":"j-$inicioMs","janelaInicio":"$inicio","janelaFim":"${inicio.plusMillis(hora)}",
             "servidorAgora":"${Instant.ofEpochMilli(agoraMs)}",
             "itens":[
               {"itemProgramacaoId":"i1","criativoId":"c1","duracaoSegundos":600,"url":"$base/midia/comercial.mp4",
                "institucional":false,"contabiliza":true},
               {"itemProgramacaoId":"i2","criativoId":null,"duracaoSegundos":600,"url":"$base/midia/institucional.mp4",
                "institucional":true,"contabiliza":false}]}
        """.trimIndent()
    }

    private fun linhasNaFila(): Int = ProofOfPlayDb(h.contexto).contarPendentes()

    @Test
    fun `programacao vencida sem internet nao repete comercial e cai no institucional`() {
        // O servidor diz que já passou do fim da janela guardada: a TV ficou
        // horas offline e a única playlist que tem é a das 10 h.
        val agora = System.currentTimeMillis()
        val inicioVencido = Math.floorDiv(agora, hora) * hora - 12 * hora
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, playlist(inicioVencido, agora))
        // O institucional da Mostraí já estava guardado na TV.
        h.contexto.getSharedPreferences("mostrai_institucional", Context.MODE_PRIVATE).edit()
            .putString("itens", """[{"criativoId":null,"url":"${h.servidor.baseUrl}/midia/institucional.mp4","duracaoSegundos":600,"contentHash":null}]""")
            .commit()

        val atividade = h.subir().get()
        h.esperar { h.servidor.contar("/midia/institucional.mp4") > 0 }
        h.avancar(1_000L)

        assertEquals("o comercial vencido foi tocado", 0, h.servidor.contar("/midia/comercial.mp4"))
        assertEquals("comprovante inventado", 0, linhasNaFila())
        assertEquals(EstadoPlayer.NO_PLAYLIST, estado(atividade))
    }

    @Test
    fun `retentativa de um minuto nao reinicia o institucional de reserva`() {
        val agora = System.currentTimeMillis()
        h.servidor.rotas["/playlist/"] =
            ServidorDeTeste.Resposta(200, playlist(Math.floorDiv(agora, hora) * hora - 12 * hora, agora))
        h.contexto.getSharedPreferences("mostrai_institucional", Context.MODE_PRIVATE).edit()
            .putString("itens", """[{"criativoId":null,"url":"${h.servidor.baseUrl}/midia/institucional.mp4","duracaoSegundos":600,"contentHash":null}]""")
            .commit()

        val atividade = h.subir().get()
        h.esperar { h.campo<Boolean>(atividade, "emFallback") }
        h.deixarRodar(500)
        val geracao = h.campo<Int>(atividade, "geracaoReproducao")
        repeat(4) {
            h.avancar(60_500L)
            h.deixarRodar(300)
        }

        assertTrue("a retentativa nem rodou", h.servidor.contar("/playlist/") >= 3)
        assertEquals("o vídeo de reserva recomeçou a cada minuto", geracao, h.campo<Int>(atividade, "geracaoReproducao"))
    }

    @Test
    fun `programacao vencida sem institucional guardado mostra o cartao, nunca o comercial`() {
        val agora = System.currentTimeMillis()
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(
            200,
            playlist(Math.floorDiv(agora, hora) * hora - 3 * hora, agora).replace(""""institucional":true""", """"institucional":false"""),
        )

        val atividade = h.subir().get()
        h.esperar { estado(atividade) == EstadoPlayer.NO_PLAYLIST }
        h.avancar(1_000L)

        assertEquals(0, h.servidor.contar("/midia/comercial.mp4"))
        assertEquals(0, linhasNaFila())
    }

    @Test
    fun `reboot sem internet retoma a programacao ainda valida`() {
        val agora = System.currentTimeMillis()
        val inicio = Math.floorDiv(agora, hora) * hora
        // Antes do reboot: playlist válida guardada, piso de 1 min atrás.
        Settings.Global.putInt(h.contexto.contentResolver, Settings.Global.BOOT_COUNT, 5)
        PlaylistCache(h.contexto).salvar(playlist(inicio, agora), RelogioJanela.agora(Instant.ofEpochMilli(agora).toString()))
        h.contexto.getSharedPreferences(RelogioConfiavel.ARQUIVO, Context.MODE_PRIVATE).edit()
            .putLong("piso_servidor_ms", agora - 60_000L).commit()
        // Reboot (âncora perdida) e sem internet.
        Settings.Global.putInt(h.contexto.contentResolver, Settings.Global.BOOT_COUNT, 6)
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(503, "")

        val atividade = h.subir().get()
        h.esperar { estado(atividade) == EstadoPlayer.PLAYING }

        assertTrue("não voltou ao comercial ainda autorizado", h.servidor.contar("/midia/comercial.mp4") > 0 || linhasNaFila() > 0)
    }

    @Test
    fun `relogio da TV atras do ultimo instante do servidor nao autoriza comercial depois do reboot`() {
        val agora = System.currentTimeMillis()
        val inicio = Math.floorDiv(agora, hora) * hora
        Settings.Global.putInt(h.contexto.contentResolver, Settings.Global.BOOT_COUNT, 5)
        PlaylistCache(h.contexto).salvar(playlist(inicio, agora), RelogioJanela.agora(Instant.ofEpochMilli(agora).toString()))
        // O servidor já mostrou um instante 2 h à frente do relógio desta TV:
        // ela voltou do reboot com a hora errada (sem RTC).
        h.contexto.getSharedPreferences(RelogioConfiavel.ARQUIVO, Context.MODE_PRIVATE).edit()
            .putLong("piso_servidor_ms", agora + 2 * hora).commit()
        Settings.Global.putInt(h.contexto.contentResolver, Settings.Global.BOOT_COUNT, 6)
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(503, "")

        val atividade = h.subir().get()
        h.esperar { estado(atividade) == EstadoPlayer.NO_PLAYLIST }
        h.avancar(1_000L)

        assertEquals(0, h.servidor.contar("/midia/comercial.mp4"))
        assertEquals(0, linhasNaFila())
    }

    @Test
    fun `sessao operacional abre com o ciclo e fecha com o motivo`() {
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo(duracao = 600))
        h.servidor.rotas["/player/M-0001/operacao"] = ServidorDeTeste.Resposta(404, "")

        val controle = h.subir()
        h.esperar { RegistroOperacional(h.contexto).pendentes().any { it.aberta } }

        controle.pause().stop()
        h.esperar { RegistroOperacional(h.contexto).pendentes().none { it.aberta } }

        val sessao = RegistroOperacional(h.contexto).pendentes().single()
        assertEquals("parou", sessao.motivoFim)
        assertEquals("M-0001", sessao.dispositivoId)
    }

    @Test
    fun `sessao aberta antes da ancora nao leva o relogio da TV como se fosse do servidor`() {
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(503, "")
        h.servidor.rotas["/player/M-0001/operacao"] = ServidorDeTeste.Resposta(404, "")
        // O relógio da TV passa no piso (seria aceito para decidir o horário),
        // mas não é o do servidor.
        h.contexto.getSharedPreferences(RelogioConfiavel.ARQUIVO, Context.MODE_PRIVATE).edit()
            .putLong("piso_servidor_ms", System.currentTimeMillis() - 60_000L).commit()

        h.subir()
        h.esperar { RegistroOperacional(h.contexto).pendentes().any { it.aberta } }

        val sessao = RegistroOperacional(h.contexto).pendentes().single { it.aberta }
        assertEquals(null, sessao.inicioServidorMs)
    }

    @Test
    fun `sessoes operacionais vao ao servidor e saem da fila so com confirmacao`() {
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo(duracao = 600))
        // Uma sessão de antes (a TV operou offline e reiniciou).
        val anterior = RegistroOperacional(h.contexto).abrir("M-0001", null)!!
        h.servidor.rotas["/player/M-0001/operacao"] = ServidorDeTeste.Resposta(
            200,
            """{"resultados":[{"sessaoId":"$anterior","status":"registrada"}]}""",
        )

        h.subir()
        h.esperar { h.servidor.contar("/player/M-0001/operacao") > 0 }
        h.esperar { RegistroOperacional(h.contexto).pendentes().none { it.sessaoId == anterior } }

        val enviada = h.servidor.detalhadas
            .filter { it.caminho.startsWith("/player/M-0001/operacao") }
            .flatMap { req -> JSONObject(req.corpo).getJSONArray("sessoes").let { a -> (0 until a.length()).map(a::getJSONObject) } }
            .first { it.getString("sessaoId") == anterior }
        assertTrue("sessão interrompida deve ir encerrada", enviada.getBoolean("encerrada"))
        assertEquals(RegistroOperacional.MOTIVO_INTERROMPIDA, enviada.getString("motivo"))
    }

    @Test
    fun `sem a rota no servidor, as sessoes ficam guardadas na TV e a frota nao insiste a cada minuto`() {
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo(duracao = 600))
        h.servidor.rotas["/player/M-0001/operacao"] = ServidorDeTeste.Resposta(404, "")
        // Uma sessão de antes do reinício: encerrada e ainda não confirmada,
        // o caso que mandava a cada tique da fila.
        RegistroOperacional(h.contexto).abrir("M-0001", null)

        h.subir()
        h.esperar { h.servidor.contar("/player/M-0001/operacao") > 0 }
        repeat(5) {
            h.avancar(60_000L)
            h.deixarRodar(1_000)
        }

        assertFalse(RegistroOperacional(h.contexto).pendentes().isEmpty())
        assertTrue(
            "404 a cada minuto: ${h.servidor.contar("/player/M-0001/operacao")} envios em 5 min",
            h.servidor.contar("/player/M-0001/operacao") <= 2,
        )
    }

    @Test
    fun `programacao vencida tenta a playlist de novo em um minuto, nao em quinze`() {
        val agora = System.currentTimeMillis()
        val horaAtual = Math.floorDiv(agora, hora) * hora
        // A busca da virada ainda trouxe a hora anterior (servidor atrasado);
        // a seguinte já traz a hora certa.
        h.servidor.emSequencia("/playlist/", ServidorDeTeste.Resposta(200, playlist(horaAtual - hora, agora)))
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, playlist(horaAtual, agora))

        val atividade = h.subir().get()
        h.esperar { estado(atividade) == EstadoPlayer.NO_PLAYLIST }
        h.avancar(61_000L)
        h.esperar { estado(atividade) == EstadoPlayer.PLAYING }

        assertEquals(2, h.servidor.contar("/playlist/"))
    }

    /** Download do comercial preso até [avancoMs] depois; devolve as linhas na fila. */
    private fun downloadLento(avancoMs: Long): Int {
        val agora = System.currentTimeMillis()
        val inicio = Math.floorDiv(agora, hora) * hora
        // O servidor diz que faltam 5 s para o fim da janela; depois disso
        // fica fora (a âncora não se renova).
        // Só o comercial: o item no ar é ele, qualquer que seja a posição.
        val soComercial = JSONObject(playlist(inicio, inicio + hora - 5_000L)).apply {
            put("itens", org.json.JSONArray().put(getJSONArray("itens").getJSONObject(0)))
        }.toString()
        h.servidor.emSequencia("/playlist/", ServidorDeTeste.Resposta(200, soComercial))
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(503, "")
        val download = java.util.concurrent.CountDownLatch(1)
        h.servidor.travas["/midia/comercial.mp4"] = download

        h.subir()
        h.esperar { h.servidor.contar("/midia/comercial.mp4") > 0 }
        h.avancar(avancoMs)
        download.countDown()
        h.deixarRodar(2_000)
        h.avancar(500L)
        h.deixarRodar(500)
        return linhasNaFila()
    }

    @Test
    fun `janela que vence durante o download nao vira comprovante nem vai ao ar`() {
        assertEquals("comprovante de janela vencida", 0, downloadLento(avancoMs = 10_000L))
    }

    @Test
    fun `download lento dentro da janela ainda vai ao ar (controle do teste acima)`() {
        assertTrue(downloadLento(avancoMs = 1_000L) > 0)
    }
}
