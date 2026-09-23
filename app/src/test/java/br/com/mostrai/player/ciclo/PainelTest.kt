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
}
