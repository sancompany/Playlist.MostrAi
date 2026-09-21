package br.com.mostrai.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Peça institucional desenhada no próprio aparelho.
 *
 * O backend manda o item institucional sem url justamente para o player
 * preencher o buraco sem baixar nada.
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

    private val fundo = Paint().apply { color = Color.parseColor("#0B0B0F") }

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fundo)

        val vmin = min(width, height).toFloat()
        marca.textSize = vmin * 0.14f
        secundario.textSize = vmin * 0.045f

        val cx = width / 2f
        val cy = height / 2f
        canvas.drawText("Mostraí", cx, cy, marca)

        legenda?.let {
            canvas.drawText(it, cx, cy + vmin * 0.12f, secundario)
        }
    }
}
