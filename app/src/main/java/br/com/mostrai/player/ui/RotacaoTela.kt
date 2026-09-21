package br.com.mostrai.player.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Aplica margem de overscan e rotação de tela num par raiz/rotor.
 *
 * Usado por `PlayerActivity` e `PainelActivity` — as duas compartilham o
 * mesmo padrão de layout: `raiz` preenche a tela física inteira (o
 * framebuffer nativo do painel, sempre landscape), `rotor` carrega o
 * conteúdo de verdade e é quem gira.
 *
 * Rotação existe porque o Android não tem como saber que o painel foi
 * montado fisicamente de lado — comum em sinalização digital em espaço
 * estreito. `screenOrientation="landscape"` no manifesto continua valendo;
 * `rotor` vira do tamanho do painel físico (trocando largura por altura em
 * 90°/270°) e `View.rotation` desenha o conteúdo já compensado. Chamar de
 * dentro de `raiz.post { }` — precisa de `raiz` já medida.
 */
object RotacaoTela {

    fun aplicar(raiz: View, rotor: FrameLayout, margemVmin: Float, rotacaoGraus: Int) {
        val vmin = min(raiz.width, raiz.height)
        val px = if (margemVmin > 0f) (vmin * margemVmin / 100f).roundToInt() else 0
        raiz.setPadding(px, px, px, px)

        val larguraDisponivel = raiz.width - raiz.paddingLeft - raiz.paddingRight
        val alturaDisponivel = raiz.height - raiz.paddingTop - raiz.paddingBottom
        if (larguraDisponivel <= 0 || alturaDisponivel <= 0) return

        val girado = rotacaoGraus == 90 || rotacaoGraus == 270
        val larguraRotor = if (girado) alturaDisponivel else larguraDisponivel
        val alturaRotor = if (girado) larguraDisponivel else alturaDisponivel

        rotor.layoutParams = FrameLayout.LayoutParams(larguraRotor, alturaRotor).apply {
            gravity = Gravity.CENTER
        }
        rotor.pivotX = larguraRotor / 2f
        rotor.pivotY = alturaRotor / 2f
        rotor.rotation = rotacaoGraus.toFloat()
    }
}
