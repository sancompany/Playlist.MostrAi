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
        // A tela segue no ar com o institucional: IDLE (conta como operação),
        // não NO_PLAYLIST (que o servidor lê como falha).
        h.esperar { estado(atividade) == EstadoPlayer.IDLE }
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

    /** O que a tela M-0001 ainda tem para mandar (só com âncora do servidor). */
    private fun segmentos(): List<RegistroOperacional.Segmento> =
        RegistroOperacional(h.contexto).pendentes("M-0001")

    private fun enviados(): List<JSONObject> = h.servidor.detalhadas
        .filter { it.caminho.startsWith("/player/M-0001/operacao") }
        .flatMap { req -> JSONObject(req.corpo).getJSONArray("segmentos").let { a -> (0 until a.length()).map(a::getJSONObject) } }

    @Test
    fun `segmento operacional abre quando a tela exibe e fecha quando para`() {
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo(duracao = 600))
        h.servidor.rotas["/player/M-0001/operacao"] = ServidorDeTeste.Resposta(404, "")

        val controle = h.subir()
        h.esperar { estado(controle.get()) == EstadoPlayer.PLAYING }
        h.avancar(40_000L) // um checkpoint do sinal de vida estende o aberto
        h.esperar { segmentos().any { it.aberto && it.duracaoMs > 0 } }

        controle.pause().stop()
        h.esperar { segmentos().isNotEmpty() && segmentos().none { it.aberto } }

        val s = segmentos().single()
        assertEquals("M-0001", s.dispositivoId)
        assertTrue("sem âncora do servidor", s.deslocamentoMs != null)
        assertTrue(s.duracaoMs > 0)
    }

    @Test
    fun `sem ancora do servidor neste boot o segmento espera, nunca vai com o relogio da TV`() {
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(503, "")
        h.servidor.rotas["/player/M-0001/operacao"] = ServidorDeTeste.Resposta(200, """{"resultados":[]}""")
        // Programação válida guardada e relógio confiável: a tela exibe offline.
        val agora = System.currentTimeMillis()
        Settings.Global.putInt(h.contexto.contentResolver, Settings.Global.BOOT_COUNT, 5)
        PlaylistCache(h.contexto).salvar(
            playlist(Math.floorDiv(agora, hora) * hora, agora),
            RelogioJanela.agora(Instant.ofEpochMilli(agora).toString()),
        )
        h.contexto.getSharedPreferences(RelogioConfiavel.ARQUIVO, Context.MODE_PRIVATE).edit()
            .putLong("piso_servidor_ms", agora - 60_000L).commit()
        Settings.Global.putInt(h.contexto.contentResolver, Settings.Global.BOOT_COUNT, 6)

        val atividade = h.subir().get()
        h.esperar { estado(atividade) == EstadoPlayer.PLAYING }
        h.avancar(40_000L)
        h.deixarRodar(1_000)

        assertFalse("âncora de um boot sem servidor", RegistroOperacional(h.contexto).ancorado())
        assertTrue("segmento sem âncora foi para a fila de envio", segmentos().isEmpty())
        assertTrue("segmento sem âncora foi enviado", enviados().isEmpty())
        assertTrue("o tempo não ficou guardado", RegistroOperacional(h.contexto).resumoPendente().first > 0)
    }

    @Test
    fun `segmentos vao ao servidor no formato do contrato e saem da fila so com ok`() {
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo(duracao = 600))
        h.servidor.rotas["/player/M-0001/operacao"] = ServidorDeTeste.Resposta(404, "")

        val controle = h.subir()
        h.esperar { estado(controle.get()) == EstadoPlayer.PLAYING }
        h.avancar(40_000L)
        h.esperar { segmentos().any { it.duracaoMs > 0 } }
        controle.pause().stop()
        h.esperar { segmentos().isNotEmpty() && segmentos().none { it.aberto } }
        val s = segmentos().single()

        // Volta com o servidor confirmando: o fechado sai da fila.
        h.servidor.rotas["/player/M-0001/operacao"] = ServidorDeTeste.Resposta(
            200,
            """{"resultados":[{"bootId":"${s.bootId}","seq":${s.seq},"status":"ok"}]}""",
        )
        controle.start().resume()
        h.esperar { segmentos().none { it.bootId == s.bootId && it.seq == s.seq } }

        val corpo = enviados().first { it.getString("bootId") == s.bootId && it.getInt("seq") == s.seq }
        assertEquals(setOf("bootId", "seq", "inicio", "fim"), corpo.keys().asSequence().toSet())
        val inicio = Instant.parse(corpo.getString("inicio"))
        val fim = Instant.parse(corpo.getString("fim"))
        assertTrue("fim antes do início", !fim.isBefore(inicio))
        assertTrue(Regex("^[A-Za-z0-9._:-]{1,64}$").matches(s.bootId))
    }

    @Test
    fun `sem a rota no servidor, os segmentos ficam guardados na TV e a frota nao insiste a cada minuto`() {
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo(duracao = 600))
        h.servidor.rotas["/player/M-0001/operacao"] = ServidorDeTeste.Resposta(404, "")

        val controle = h.subir()
        h.esperar { estado(controle.get()) == EstadoPlayer.PLAYING }
        h.avancar(40_000L)
        h.esperar { segmentos().any { it.duracaoMs > 0 } }
        // Fechado e não confirmado: o caso que mandava a cada tique da fila.
        controle.pause().stop()
        h.esperar { segmentos().isNotEmpty() && segmentos().none { it.aberto } }
        controle.start().resume()
        h.esperar { h.servidor.contar("/player/M-0001/operacao") > 0 }
        val antes = h.servidor.contar("/player/M-0001/operacao")
        repeat(5) {
            h.avancar(60_000L)
            h.deixarRodar(1_000)
        }

        assertFalse(segmentos().isEmpty())
        assertTrue(
            "404 a cada minuto: ${h.servidor.contar("/player/M-0001/operacao") - antes} envios a mais em 5 min",
            h.servidor.contar("/player/M-0001/operacao") - antes <= 1,
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
        // A playlist vencida trouxe o institucional: reserva no ar, IDLE.
        h.esperar { h.campo<Boolean>(atividade, "emFallback") && estado(atividade) == EstadoPlayer.IDLE }
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
