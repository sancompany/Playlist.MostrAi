package br.com.mostrai.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import br.com.mostrai.player.R
import kotlin.math.hypot
import kotlin.math.min

/**
 * Peça institucional desenhada no próprio aparelho.
 *
 * [EstadoInstitucional.PADRAO] é o item institucional que o próprio backend
 * manda (sem url) quando não há programação pra aquela hora — desenhado
 * aqui (degradê + legenda), não é arte fixa. Os outros três estados são
 * situações locais do aparelho (nunca vêm do backend) e usam a arte de
 * marca entregue pelo dono, um PNG cheio por estado — ver
 * `docs/funcional.md`, seção 4.
 */
enum class EstadoInstitucional(private val drawableRes: Int?) {
    PADRAO(null),
    NAO_PROVISIONADO(R.drawable.institucional_nao_provisionado),
    ERRO_CARREGAR(R.drawable.institucional_erro),
    CARREGANDO(R.drawable.institucional_carregando);

    fun drawable(): Int? = drawableRes
}

class TelaInstitucional @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var legenda: String? = null
        set(valor) {
            field = valor
            invalidate()
        }

    var estado: EstadoInstitucional = EstadoInstitucional.PADRAO
        set(valor) {
            if (field == valor) return
            field = valor
            bitmapEstado = null
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

    /** Bitmap do estado atual, decodificado sob demanda e recortado até o próximo troca de [estado]. */
    private var bitmapEstado: Bitmap? = null

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
        val drawableRes = estado.drawable()
        if (drawableRes != null) {
            desenharBitmapDoEstado(canvas, drawableRes)
            return
        }
        desenharPadrao(canvas)
    }

    /**
     * As quatro artes (erro, carregando, não provisionado) já vêm no formato
     * portrait cheio (mesma proporção do `rotor` compensado por
     * [RotacaoTela]) — "fit center" preserva a proporção sem cortar nem
     * distorcer, mesmo quando a margem de overscan ou uma TV com proporção
     * ligeiramente diferente deixar uma folga nas bordas.
     */
    private fun desenharBitmapDoEstado(canvas: Canvas, drawableRes: Int) {
        canvas.drawColor(Color.WHITE)
        val bitmap = bitmapEstado ?: BitmapFactory.decodeResource(resources, drawableRes)?.also { bitmapEstado = it }
        bitmap ?: return

        val escala = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val larguraDestino = bitmap.width * escala
        val alturaDestino = bitmap.height * escala
        val left = (width - larguraDestino) / 2f
        val top = (height - alturaDestino) / 2f
        canvas.drawBitmap(
            bitmap,
            Rect(0, 0, bitmap.width, bitmap.height),
            RectF(left, top, left + larguraDestino, top + alturaDestino),
            null,
        )
    }

    private fun desenharPadrao(canvas: Canvas) {
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
