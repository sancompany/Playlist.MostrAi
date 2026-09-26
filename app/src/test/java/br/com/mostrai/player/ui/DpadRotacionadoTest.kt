package br.com.mostrai.player.ui

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import br.com.mostrai.player.Produto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Conteúdo girado 90° no sentido horário: a direção de layout "cima" aparece
 * para a direita. Apertar DIREITA no controle precisa andar para "cima" no
 * layout — senão o foco vai para onde o espectador não esperava.
 */
class DpadRotacionadoTest {

    @Test
    fun `a 90 graus cada seta anda uma casa no sentido anti-horario do layout`() {
        assertEquals(KEYCODE_DPAD_UP, DpadRotacionado.remapear(KEYCODE_DPAD_RIGHT, 90))
        assertEquals(KEYCODE_DPAD_RIGHT, DpadRotacionado.remapear(KEYCODE_DPAD_DOWN, 90))
        assertEquals(KEYCODE_DPAD_DOWN, DpadRotacionado.remapear(KEYCODE_DPAD_LEFT, 90))
        assertEquals(KEYCODE_DPAD_LEFT, DpadRotacionado.remapear(KEYCODE_DPAD_UP, 90))
    }

    @Test
    fun `a 270 graus, a correcao fisica prevista, o mapa e o inverso`() {
        listOf(KEYCODE_DPAD_UP, KEYCODE_DPAD_RIGHT, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT).forEach {
            assertEquals(it, DpadRotacionado.remapear(DpadRotacionado.remapear(it, 90), 270))
        }
    }

    @Test
    fun `sem rotacao e teclas que nao sao seta passam iguais`() {
        assertEquals(KEYCODE_DPAD_RIGHT, DpadRotacionado.remapear(KEYCODE_DPAD_RIGHT, 0))
        assertEquals(KEYCODE_DPAD_CENTER, DpadRotacionado.remapear(KEYCODE_DPAD_CENTER, Produto.ROTACAO_GRAUS))
    }
}
