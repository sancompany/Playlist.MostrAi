package br.com.mostrai.player.ciclo

import android.content.Intent
import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.config.ConfigAparelho
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/**
 * `PlayerActivity` é exportada (LAUNCHER): qualquer app instalado na TV pode
 * abri-la com extras. Nenhum extra provisiona nada — nem na build de
 * depuração. O único caminho de credencial é a tela de instalação.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SegurancaLocalTest {

    private lateinit var h: Harness

    @Before
    fun preparar() {
        h = Harness()
    }

    @After
    fun encerrar() = h.encerrar()

    @Test
    fun `extras de intent nao provisionam o aparelho`() {
        val intent = Intent(h.contexto, PlayerActivity::class.java)
            .putExtra("dispositivoId", "M-0666")
            .putExtra("chaveAparelho", "chave-de-outro-app")
            .putExtra("baseUrl", "https://atacante.exemplo")
            .putExtra("rotacaoTela", 180)

        org.robolectric.Robolectric.buildActivity(PlayerActivity::class.java, intent).setup()
        h.idle()

        val config = ConfigAparelho(h.contexto)
        assertNull(config.dispositivoId)
        assertNull(config.chaveAparelho)
    }

    @Test
    fun `tecla voltar do controle nao fecha o player`() {
        // BUG-027: um VOLTAR acidental no controle da loja encerrava a
        // Activity no meio do anúncio. Agora só pede o PIN de saída.
        h.provisionar()
        val atividade = h.subir().get()

        atividade.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
        atividade.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        h.idle()

        assertFalse("VOLTAR fechou o player", atividade.isFinishing)
    }
}
