package br.com.mostrai.player.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.Produto
import br.com.mostrai.player.config.MargensOverscan
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Rotação fixa de 90° com as quatro margens da config (contrato §6: a
 * margem é visual — "superior" é o topo que o espectador vê).
 */
@RunWith(RobolectricTestRunner::class)
class RotacaoTelaTest {

    private val contexto = ApplicationProvider.getApplicationContext<Context>()

    /** Painel 1920×1080 (framebuffer landscape), como a TV da loja. */
    private fun montar(): Pair<FrameLayout, FrameLayout> {
        val raiz = FrameLayout(contexto)
        val rotor = FrameLayout(contexto)
        raiz.addView(rotor)
        raiz.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        raiz.layout(0, 0, 1920, 1080)
        return raiz to rotor
    }

    @Test
    fun `a rotacao do produto e 90 graus, fixa`() {
        assertEquals(90, Produto.ROTACAO_GRAUS)
    }

    @Test
    fun `a 90 graus o quadro vira retrato e gira`() {
        val (raiz, rotor) = montar()

        RotacaoTela.aplicar(raiz, rotor, MargensOverscan(), Produto.ROTACAO_GRAUS)

        assertEquals(1080, rotor.layoutParams.width)
        assertEquals(1920, rotor.layoutParams.height)
        assertEquals(90f, rotor.rotation)
        assertEquals(0, rotor.paddingTop + rotor.paddingBottom + rotor.paddingLeft + rotor.paddingRight)
    }

    @Test
    fun `cada lado vai para o lado visual certo, em vmin do quadro`() {
        val (raiz, rotor) = montar()
        // vmin do quadro retrato 1080×1920 = 10,8 px.
        RotacaoTela.aplicar(raiz, rotor, MargensOverscan(topo = 1f, base = 2f, esquerda = 3f, direita = 4f), 90)

        assertEquals(11, rotor.paddingTop)
        assertEquals(22, rotor.paddingBottom)
        assertEquals(32, rotor.paddingLeft)
        assertEquals(43, rotor.paddingRight)
    }

    @Test
    fun `um lado so, sem mexer nos outros`() {
        listOf(
            MargensOverscan(topo = 5f) to intArrayOf(54, 0, 0, 0),
            MargensOverscan(direita = 5f) to intArrayOf(0, 54, 0, 0),
            MargensOverscan(base = 5f) to intArrayOf(0, 0, 54, 0),
            MargensOverscan(esquerda = 5f) to intArrayOf(0, 0, 0, 54),
        ).forEach { (margens, esperado) ->
            val (raiz, rotor) = montar()
            RotacaoTela.aplicar(raiz, rotor, margens, 90)
            val obtido = intArrayOf(rotor.paddingTop, rotor.paddingRight, rotor.paddingBottom, rotor.paddingLeft)
            assertEquals(margens.toString(), esperado.toList(), obtido.toList())
        }
    }

    @Test
    fun `margem maxima de 10 vmin em todos os lados`() {
        val (raiz, rotor) = montar()
        RotacaoTela.aplicar(raiz, rotor, MargensOverscan(10f, 10f, 10f, 10f), 90)
        assertEquals(listOf(108, 108, 108, 108), listOf(rotor.paddingTop, rotor.paddingRight, rotor.paddingBottom, rotor.paddingLeft))
    }
}
