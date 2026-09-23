package br.com.mostrai.player.ciclo

import android.view.KeyEvent
import br.com.mostrai.player.ui.PainelActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/** Ciclo 13 — painel de manutenção esquecido aberto. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PainelTest {

    private lateinit var h: Harness

    @Before
    fun preparar() {
        h = Harness()
    }

    @After
    fun encerrar() = h.encerrar()

    @Test
    fun `painel esquecido aberto fecha sozinho`() {
        // O player segue tocando (e gerando comprovante) atrás do painel, que
        // cobre 90% da tela. Esquecido aberto, a loja exibia o diagnóstico
        // por dias com os anúncios contando por baixo.
        val painel = Robolectric.buildActivity(PainelActivity::class.java).setup().get()

        h.avancar(10 * 60_000L)

        assertTrue("painel continuou aberto", painel.isFinishing)
    }

    @Test
    fun `painel em uso nao fecha no meio da digitacao`() {
        val painel = Robolectric.buildActivity(PainelActivity::class.java).setup().get()

        repeat(5) {
            h.avancar(60_000L)
            painel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            painel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        }

        assertFalse(painel.isFinishing)
    }

    @Test
    fun `painel nunca mostra a chave inteira nem credencial na url`() {
        // Invariante 14: a tela fica num comércio.
        br.com.mostrai.player.config.ConfigAparelho(h.contexto).apply {
            baseUrl = "https://usuario:senha-secreta@api.exemplo.com/v1?token=tk-secreto"
            dispositivoId = "tela-1"
            chaveAparelho = "chave-muito-secreta-123456789"
            pinPainel = "1234"
        }
        val painel = Robolectric.buildActivity(PainelActivity::class.java).setup().get()

        val teclado = painel.findViewById<android.widget.GridLayout>(br.com.mostrai.player.R.id.teclado)
        "1234".forEach { d ->
            (0 until teclado.childCount).map { teclado.getChildAt(it) as android.widget.TextView }
                .first { it.text.toString() == d.toString() }
                .performClick()
        }
        h.idle()

        val texto = painel.findViewById<android.widget.TextView>(br.com.mostrai.player.R.id.info).text.toString()
        assertTrue("painel não abriu: $texto", texto.contains("Servidor"))
        listOf("chave-muito-secreta-123456789", "senha-secreta", "usuario:", "tk-secreto").forEach {
            assertFalse("painel mostrou '$it'", texto.contains(it))
        }
    }
}
