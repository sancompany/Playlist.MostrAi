package br.com.mostrai.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Peça institucional desenhada no próprio aparelho.
 *
 * O backend manda o item institucional sem url justamente para o player
 * preencher o buraco sem baixar nada. Fundo em degradê (em vez de cor
 * chapada) — provisório até o dono trazer a arte de marca de verdade
 * (ícone/fundo, ver `docs/proximas-versoes.md`).
 */
class TelaInstitucional @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var legenda: String? = null
        set(valor) {
            field = valor
            invalidate()
        }

    private val fundo = Paint()

    private val marca = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val secundario = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA0A6")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val linha = Paint().apply { color = Color.parseColor("#2A2F3A") }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // Degradê radial, centro mais claro — dá profundidade sem precisar
        // de nenhum recurso externo. Recalculado só quando o tamanho muda,
        // não a cada onDraw.
        val raio = hypot(w / 2f, h / 2f)
        fundo.shader = RadialGradient(
            w / 2f, h / 2f, raio,
            intArrayOf(Color.parseColor("#181D27"), Color.parseColor("#08090C")),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fundo)

        val vmin = min(width, height).toFloat()
        marca.textSize = vmin * 0.14f
        secundario.textSize = vmin * 0.045f

        val cx = width / 2f
        val cy = height / 2f
        canvas.drawText("Mostraí", cx, cy, marca)

        val larguraLinha = vmin * 0.10f
        val yLinha = cy + vmin * 0.05f
        canvas.drawRect(cx - larguraLinha, yLinha, cx + larguraLinha, yLinha + vmin * 0.004f, linha)

        legenda?.let {
            canvas.drawText(it, cx, cy + vmin * 0.14f, secundario)
        }
    }
}
