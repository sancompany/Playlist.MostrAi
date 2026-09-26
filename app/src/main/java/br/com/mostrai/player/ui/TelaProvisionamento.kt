package br.com.mostrai.player.ui

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import br.com.mostrai.player.R
import br.com.mostrai.player.provisionamento.CodigoInstalacao

/**
 * Tela da primeira abertura (e de depois de uma revogação): ID da tela +
 * código de instalação + CONECTAR. Nada de servidor, rotação, PIN ou
 * diagnóstico (contrato §3).
 *
 * Só cuida de digitação e exibição; quem conecta é a Activity, por
 * [aoConectar], e devolve o resultado em [mostrarMensagem]/[liberar].
 */
class TelaProvisionamento(
    private val raiz: View,
    private val aoConectar: (id: String, codigo: String) -> Unit,
) {
    private enum class Campo { ID, CODIGO }

    private val campoId: TextView = raiz.findViewById(R.id.campoId)
    private val campoCodigo: TextView = raiz.findViewById(R.id.campoCodigo)
    private val botao: TextView = raiz.findViewById(R.id.botaoConectar)
    private val mensagem: TextView = raiz.findViewById(R.id.mensagemProvisionamento)
    private val teclado: GridLayout = raiz.findViewById(R.id.tecladoProvisionamento)

    private val digitosId = StringBuilder()
    private val codigo = StringBuilder()
    private var ativo = Campo.ID
    private var ocupado = false

    val visivel: Boolean get() = raiz.visibility == View.VISIBLE

    init {
        campoId.setOnClickListener { ativar(Campo.ID) }
        campoCodigo.setOnClickListener { ativar(Campo.CODIGO) }
        botao.setOnClickListener { conectar() }
        montarTeclado()
    }

    /** Abre a tela. [idConhecido] (ex.: depois de revogação) já vem preenchido — não é segredo. */
    fun mostrar(idConhecido: String?) {
        digitosId.setLength(0)
        idConhecido?.filter(Char::isDigit)?.take(MAX_DIGITOS_ID)?.let(digitosId::append)
        codigo.setLength(0)
        ocupado = false
        mensagem.visibility = View.INVISIBLE
        ativar(if (digitosId.isEmpty()) Campo.ID else Campo.CODIGO)
        atualizarCampos()
        raiz.visibility = View.VISIBLE
        teclado.getChildAt(0)?.requestFocus()
    }

    /** Some com a tela e com o código digitado — ele não vale mais nada depois de usado. */
    fun esconder() {
        codigo.setLength(0)
        atualizarCampos()
        raiz.visibility = View.GONE
    }

    fun mostrarMensagem(texto: String) {
        mensagem.text = texto
        mensagem.visibility = View.VISIBLE
    }

    /** Resposta chegou: aceita nova tentativa. O código errado some; o ID fica. */
    fun liberar(apagarCodigo: Boolean) {
        ocupado = false
        if (apagarCodigo) codigo.setLength(0)
        atualizarCampos()
    }

    /** Teclas físicas do controle (números) e de um teclado USB, se houver. */
    fun aoTeclar(evento: KeyEvent): Boolean {
        if (!visivel || evento.action != KeyEvent.ACTION_DOWN) return false
        if (evento.keyCode == KeyEvent.KEYCODE_DEL) {
            apagar()
            return true
        }
        val caractere = evento.unicodeChar.toChar().uppercaseChar()
        if (caractere.isLetterOrDigit()) {
            digitar(caractere)
            return true
        }
        return false
    }

    private fun ativar(campo: Campo) {
        ativo = campo
        campoId.isSelected = campo == Campo.ID
        campoCodigo.isSelected = campo == Campo.CODIGO
    }

    private fun digitar(caractere: Char) {
        if (ocupado) return
        when (ativo) {
            Campo.ID -> if (caractere.isDigit() && digitosId.length < MAX_DIGITOS_ID) digitosId.append(caractere)
            Campo.CODIGO -> if (caractere in CodigoInstalacao.ALFABETO && codigo.length < CodigoInstalacao.TAMANHO) {
                codigo.append(caractere)
            }
        }
        mensagem.visibility = View.INVISIBLE
        atualizarCampos()
    }

    private fun apagar() {
        if (ocupado) return
        val alvo = if (ativo == Campo.ID) digitosId else codigo
        if (alvo.isNotEmpty()) alvo.setLength(alvo.length - 1)
        atualizarCampos()
    }

    private fun conectar() {
        if (ocupado) return
        ocupado = true
        mensagem.text = raiz.context.getString(R.string.prov_conectando)
        mensagem.visibility = View.VISIBLE
        aoConectar(digitosId.toString(), codigo.toString())
    }

    private fun atualizarCampos() {
        campoId.text = "M-$digitosId"
        campoCodigo.text = CodigoInstalacao.formatarParcial(codigo.toString())
    }

    private fun montarTeclado() {
        TECLAS.forEach { tecla ->
            val vista = TextView(raiz.context).apply {
                text = tecla
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColorStateList(context, R.color.cor_tecla))
                setBackgroundResource(R.drawable.fundo_tecla)
                isFocusable = true
                setOnClickListener { if (tecla == APAGAR) apagar() else digitar(tecla[0]) }
            }
            val lado = raiz.resources.getDimensionPixelSize(R.dimen.tecla)
            teclado.addView(
                vista,
                GridLayout.LayoutParams().apply {
                    width = lado
                    height = lado
                    setMargins(3, 3, 3, 3)
                },
            )
        }
    }

    companion object {
        private const val APAGAR = "⌫"

        /** Mesmo teto do backend: até 10 dígitos. */
        const val MAX_DIGITOS_ID = 10

        /** Dígitos e as letras do alfabeto do código de instalação (sem O, I, L). */
        val TECLAS: List<String> =
            ("1234567890" + CodigoInstalacao.ALFABETO.filter(Char::isLetter)).map(Char::toString) + APAGAR
    }
}
