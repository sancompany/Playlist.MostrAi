package br.com.mostrai.player.ui

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import br.com.mostrai.player.R
import br.com.mostrai.player.config.ConfigAparelho

/**
 * Pedido de PIN para sair do Player (contrato §6). Função única: autorizar
 * a saída. PIN global de 4 a 8 dígitos vindo da config — nunca um padrão.
 *
 * O vídeo segue tocando por baixo. PIN errado mantém o Player; sem tecla
 * por [INATIVIDADE_MS], o pedido some sozinho — esquecido aberto, cobriria
 * o anúncio que continua contando (BUG-028).
 */
class TelaPinSaida(
    private val raiz: View,
    private val config: ConfigAparelho,
    private val aoAutorizar: () -> Unit,
) {
    private val display: TextView = raiz.findViewById(R.id.displayPin)
    private val erro: TextView = raiz.findViewById(R.id.erroPin)
    private val teclado: GridLayout = raiz.findViewById(R.id.tecladoPin)
    private val handler = Handler(Looper.getMainLooper())
    private val fecharPorInatividade = Runnable { esconder() }

    private val digitado = StringBuilder()
    private var pinEsperado: String? = null

    val visivel: Boolean get() = raiz.visibility == View.VISIBLE

    init {
        montarTeclado()
    }

    /** Sem PIN recebido, a saída autorizada não existe: nada acontece. */
    fun pedir(): Boolean {
        val pin = config.pinSaida ?: return false
        pinEsperado = pin
        digitado.setLength(0)
        erro.visibility = View.INVISIBLE
        atualizarDisplay()
        raiz.visibility = View.VISIBLE
        mostrarBloqueioSeHouver()
        teclado.getChildAt(0)?.requestFocus()
        registrarAtividade()
        return true
    }

    fun esconder() {
        handler.removeCallbacks(fecharPorInatividade)
        digitado.setLength(0)
        pinEsperado = null
        raiz.visibility = View.GONE
    }

    /** Números do próprio controle remoto, além da grade na tela. */
    fun aoTeclar(evento: KeyEvent): Boolean {
        if (!visivel || evento.action != KeyEvent.ACTION_DOWN) return false
        if (evento.keyCode == KeyEvent.KEYCODE_DEL) {
            apagar()
            return true
        }
        val caractere = evento.unicodeChar.toChar()
        if (!caractere.isDigit()) return false
        digitar(caractere)
        return true
    }

    /** Toda tecla com o pedido aberto adia o fechamento automático. */
    fun registrarAtividade() {
        handler.removeCallbacks(fecharPorInatividade)
        if (visivel) handler.postDelayed(fecharPorInatividade, INATIVIDADE_MS)
    }

    private fun digitar(digito: Char) {
        val esperado = pinEsperado ?: return
        if (mostrarBloqueioSeHouver()) return
        if (digitado.length >= esperado.length) return
        digitado.append(digito)
        erro.visibility = View.INVISIBLE
        atualizarDisplay()
        if (digitado.length < esperado.length) return

        if (digitado.toString() == esperado) {
            config.registrarPinCerto()
            esconder()
            aoAutorizar()
        } else {
            config.registrarPinErrado()
            digitado.setLength(0)
            atualizarDisplay()
            if (!mostrarBloqueioSeHouver()) {
                erro.text = raiz.context.getString(R.string.pin_errado)
                erro.visibility = View.VISIBLE
            }
        }
    }

    private fun apagar() {
        if (digitado.isNotEmpty()) digitado.setLength(digitado.length - 1)
        atualizarDisplay()
    }

    private fun mostrarBloqueioSeHouver(): Boolean {
        val restanteMs = config.pinBloqueadoPorMs()
        if (restanteMs <= 0L) return false
        erro.text = raiz.context.getString(R.string.pin_bloqueado, (restanteMs / 1000).toInt() + 1)
        erro.visibility = View.VISIBLE
        return true
    }

    private fun atualizarDisplay() {
        val tamanho = pinEsperado?.length ?: 0
        display.text = (0 until tamanho).joinToString(" ") { if (it < digitado.length) "•" else "·" }
    }

    private fun montarTeclado() {
        TECLAS.forEach { tecla ->
            val vista = TextView(raiz.context).apply {
                text = tecla
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColorStateList(context, R.color.cor_tecla))
                setBackgroundResource(R.drawable.fundo_tecla)
                isFocusable = true
                setOnClickListener { if (tecla == APAGAR) apagar() else digitar(tecla[0]) }
            }
            val lado = raiz.resources.getDimensionPixelSize(R.dimen.tecla) * 5 / 4
            teclado.addView(
                vista,
                GridLayout.LayoutParams().apply {
                    width = lado
                    height = lado
                    setMargins(4, 4, 4, 4)
                },
            )
        }
    }

    companion object {
        const val INATIVIDADE_MS = 30_000L
        private const val APAGAR = "⌫"
        private val TECLAS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0")
    }
}
