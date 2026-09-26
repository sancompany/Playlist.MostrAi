package br.com.mostrai.player.ciclo

import android.view.View
import android.widget.TextView
import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.R
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.kiosk.Watchdog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/** Saída autorizada por PIN global (contrato §6). */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SaidaCicloTest {

    private lateinit var h: Harness

    @Before
    fun preparar() {
        h = Harness()
        h.provisionar()
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo(duracao = 600))
        h.midiaNoAr()
    }

    @After
    fun encerrar() = h.encerrar()

    private fun digitar(atividade: PlayerActivity, pin: String) =
        pin.forEach { h.tecla(atividade, R.id.tecladoPin, it.toString()) }

    private fun comPin(pin: String = "4821") {
        h.aplicarConfig("""{"configVersion":0,"pinSaida":"$pin"}""")
    }

    @Test
    fun `voltar pede o PIN e o player continua por baixo`() {
        comPin()
        val atividade = h.subir().get()
        h.esperar { h.campo<String?>(atividade, "execucaoAtualId") != null }
        val execucao = h.campo<String?>(atividade, "execucaoAtualId")

        h.voltar(atividade)

        assertEquals(View.VISIBLE, h.vista<View>(atividade, R.id.telaPin).visibility)
        assertEquals("· · · ·", h.vista<TextView>(atividade, R.id.displayPin).text.toString())
        assertFalse(atividade.isFinishing)
        assertEquals("a exibição em andamento seguiu valendo", execucao, h.campo<String?>(atividade, "execucaoAtualId"))
    }

    @Test
    fun `PIN errado continua o player`() {
        comPin()
        val atividade = h.subir().get()
        h.voltar(atividade)

        digitar(atividade, "1111")

        assertFalse(atividade.isFinishing)
        assertFalse(Watchdog.saidaAutorizada(h.contexto))
        assertEquals("PIN incorreto", h.vista<TextView>(atividade, R.id.erroPin).text.toString())
        h.voltar(atividade)
        assertEquals(View.GONE, h.vista<View>(atividade, R.id.telaPin).visibility)
        assertFalse(atividade.isFinishing)
    }

    @Test
    fun `PIN certo sai e o watchdog nao reabre`() {
        comPin("73915")
        val atividade = h.subir().get()
        h.voltar(atividade)
        assertEquals("· · · · ·", h.vista<TextView>(atividade, R.id.displayPin).text.toString())

        digitar(atividade, "73915")

        assertTrue(atividade.isFinishing)
        assertTrue(Watchdog.saidaAutorizada(h.contexto))
        Watchdog.Receptor().onReceive(h.contexto, android.content.Intent())
        val app = org.robolectric.Shadows.shadowOf(h.contexto as android.app.Application)
        assertEquals("watchdog reabriu depois da saída autorizada", null, app.nextStartedActivity)
    }

    @Test
    fun `sem PIN recebido nao existe saida autorizada`() {
        val atividade = h.subir().get()

        h.voltar(atividade)

        assertEquals(View.GONE, h.vista<View>(atividade, R.id.telaPin).visibility)
        assertFalse(atividade.isFinishing)
    }

    @Test
    fun `pedido de PIN esquecido fecha sozinho`() {
        comPin()
        val atividade = h.subir().get()
        h.voltar(atividade)

        h.avancar(31_000L)

        assertEquals(View.GONE, h.vista<View>(atividade, R.id.telaPin).visibility)
    }

    @Test
    fun `PIN trocado no admin vale sem reiniciar`() {
        comPin("4821")
        val atividade = h.subir().get()
        h.aplicarConfig("""{"configVersion":1,"pinSaida":"9537"}""")

        h.voltar(atividade)
        digitar(atividade, "4821")
        assertFalse("PIN antigo ainda abria", atividade.isFinishing)

        digitar(atividade, "9537")
        assertTrue(atividade.isFinishing)
    }

    @Test
    fun `abrir o app de novo depois da saida rearma o watchdog`() {
        Watchdog.autorizarSaida(h.contexto)

        h.subir()

        assertFalse(Watchdog.saidaAutorizada(h.contexto))
    }
}
