package br.com.mostrai.player.ui

import android.view.KeyEvent

/**
 * O conteúdo é girado por `View.rotation` ([RotacaoTela]), mas a busca de
 * foco do Android anda nas coordenadas do layout, sem rotação. Numa tela
 * girada 90° no sentido horário, "direita" no layout aparece para baixo.
 *
 * Remapeia a seta do controle para a direção de layout que, depois de
 * girada, aponta para onde o espectador quis ir. Pura — testável sem View.
 */
object DpadRotacionado {

    // Ordem horária: girar o conteúdo 90° desloca cada direção uma casa.
    private val HORARIO = listOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
    )

    fun remapear(codigo: Int, rotacaoGraus: Int): Int {
        val indice = HORARIO.indexOf(codigo)
        if (indice < 0) return codigo
        val passos = Math.floorMod(rotacaoGraus / 90, 4)
        return HORARIO[Math.floorMod(indice - passos, 4)]
    }
}
