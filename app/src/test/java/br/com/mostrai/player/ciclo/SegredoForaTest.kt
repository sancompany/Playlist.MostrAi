package br.com.mostrai.player.ciclo

import android.view.View
import android.widget.TextView
import br.com.mostrai.player.R
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.ui.InfoSuporte
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

/**
 * V1 de produção (05/10/2026): nada de chave, token, cabeçalho nem URL no
 * log nem no bloco técnico atrás do PIN — mesmo nos caminhos de erro (401,
 * 5xx), que são os que mais escrevem.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SegredoForaTest {

    private lateinit var h: Harness
    private val chave = "SEGREDO-a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8"

    @Before
    fun preparar() {
        ShadowLog.clear()
        h = Harness()
        h.provisionar(chave = chave)
        h.midiaNoAr()
    }

    @After
    fun encerrar() = h.encerrar()

    private fun tudoQueFoiLogado(): String = ShadowLog.getLogs().joinToString("\n") { l ->
        listOfNotNull(l.tag, l.msg, l.throwable?.stackTraceToString()).joinToString(" ")
    }

    private fun proibidos() = listOf(chave, chave.takeLast(16), "Bearer", "Authorization", "X-Aparelho-Key")

    @Test
    fun `ciclo com erros de rede e servidor nao escreve a chave no log`() {
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo(duracao = 600))
        h.servidor.rotas["/player/"] = ServidorDeTeste.Resposta(500, """{"erro":"x"}""")
        h.servidor.rotas["/played"] = ServidorDeTeste.Resposta(503, "")

        val atividade = h.subir().get()
        h.esperar { h.servidor.contar("/player/M-0001/heartbeat") >= 1 }
        h.avancar(20_000L)
        h.deixarRodar(500)
        // Por último, a credencial recusada (401): o caminho que apaga a chave.
        h.servidor.rotas["/player/"] = ServidorDeTeste.Resposta(401, "")
        h.avancar(20_000L)
        h.esperar { !h.campo<Boolean>(atividade, "cicloAtivo") || atividade.isFinishing || h.servidor.contar("/player/M-0001/heartbeat") >= 3 }

        val log = tudoQueFoiLogado()
        proibidos().forEach { assertFalse("'$it' apareceu no log", log.contains(it)) }
    }

    @Test
    fun `bloco tecnico atras do PIN nao mostra chave, token, cabecalho nem URL`() {
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo(duracao = 600))
        h.aplicarConfig("""{"configVersion":0,"pinSaida":"4821"}""")
        val atividade = h.subir().get()
        h.esperar { h.servidor.contar("/playlist/") >= 1 }

        h.voltar(atividade)
        val info = h.vista<TextView>(atividade, R.id.infoSuporte)
        h.esperar { info.text.isNotBlank() }

        val texto = info.text.toString()
        assertEquals(View.VISIBLE, h.vista<View>(atividade, R.id.telaPin).visibility)
        assertTrue(texto.contains("M-0001"))
        (proibidos() + listOf("http", h.servidor.baseUrl, "chave", "token", "senha")).forEach {
            assertFalse("'$it' apareceu no bloco técnico:\n$texto", texto.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `texto do bloco tecnico so tem campos de diagnostico`() {
        val texto = InfoSuporte.texto(
            InfoSuporte.Dados(
                dispositivoId = "M-0001", versao = "3.0.0+4", aparelho = "TCL 32S6500S", android = "Android 8.0.0 (API 26)",
                estado = "IDLE", retornoAutomatico = false, espacoLivreBytes = 3L * 1024 * 1024 * 1024,
                ultimoErro = "DOWNLOAD_FALHOU 2026-10-05T14:00", ancoradoNesteBoot = false, online = false,
                ultimaSincronizacaoMs = null, programacaoValidaAteMs = null, programacaoAutorizadaAgora = false,
                midiaEmCache = 0, midiaTotal = 1, institucionalGuardado = 1, espacoSuficiente = true,
                comprovantesPendentes = 3, sessoesPendentes = 1, tempoOperacionalPendenteMs = 90 * 60_000L,
            ),
        )

        assertTrue(texto.contains("Estado: IDLE"))
        assertTrue(texto.contains("Espaço livre: 3072 MB"))
        assertTrue(texto.contains("Retorno automático BLOQUEADO"))
        assertTrue(texto.contains("1 h 30 min (1 segmentos) · sem hora do servidor neste boot"))
        assertTrue(texto.contains("NÃO PRONTO PARA OFFLINE"))
    }
}
