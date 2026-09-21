package br.com.mostrai.player.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import br.com.mostrai.player.config.MargensOverscan
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
 *
 * A margem de overscan é aplicada como padding em `rotor`, nunca em
 * `raiz`: `rotor` já representa o quadro visual (o que o operador enxerga
 * depois de compensada a rotação física), então aplicar o padding ali,
 * antes da transformação de rotação, faz "topo/base/esquerda/direita"
 * corresponderem direto ao que o operador vê — sem nenhuma conta a mais,
 * pra qualquer valor de `rotacaoGraus`. Aplicar em `raiz` (como a versão
 * anterior, de margem única, fazia) não funciona pra margem assimétrica:
 * a rotação de 90°/270° troca largura por altura, e um padding desigual
 * aplicado antes dessa troca não corresponde ao lado visual pretendido
 * depois dela.
 */
object RotacaoTela {

    fun aplicar(raiz: View, rotor: FrameLayout, margens: MargensOverscan, rotacaoGraus: Int) {
        val larguraRaiz = raiz.width
        val alturaRaiz = raiz.height
        if (larguraRaiz <= 0 || alturaRaiz <= 0) return

        val girado = rotacaoGraus == 90 || rotacaoGraus == 270
        val larguraRotor = if (girado) alturaRaiz else larguraRaiz
        val alturaRotor = if (girado) larguraRaiz else alturaRaiz

        rotor.layoutParams = FrameLayout.LayoutParams(larguraRotor, alturaRotor).apply {
            gravity = Gravity.CENTER
        }
        rotor.pivotX = larguraRotor / 2f
        rotor.pivotY = alturaRotor / 2f
        rotor.rotation = rotacaoGraus.toFloat()

        val vmin = min(larguraRotor, alturaRotor)
        fun paraPx(percentual: Float) = if (percentual > 0f) (vmin * percentual / 100f).roundToInt() else 0
        rotor.setPadding(paraPx(margens.esquerda), paraPx(margens.topo), paraPx(margens.direita), paraPx(margens.base))
    }
}
