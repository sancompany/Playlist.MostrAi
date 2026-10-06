package br.com.mostrai.player.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import br.com.mostrai.player.R

/**
 * Modal de atualização por pendrive, em três variações — sempre dois botões,
 * nada técnico na tela (hash, caminho, certificado ficam no bloco técnico):
 *
 * - [Modo.DISPONIVEL]: "Nova versão encontrada. Atual / Nova" → Atualizar agora;
 * - [Modo.PERMISSAO_INSTALAR]: falta liberar "instalar apps" para o Mostraí;
 * - [Modo.ACESSO_PENDRIVE]: falta o Android deixar ler o pendrive.
 *
 * O vídeo segue por baixo. Sem resposta por [INATIVIDADE_MS] o modal some
 * como se fosse "Depois": esquecido aberto, cobriria o anúncio.
 */
class TelaAtualizacao(
    private val raiz: View,
    private val aoConfirmar: (Modo) -> Unit,
    private val aoAdiar: (Modo) -> Unit,
) {
    enum class Modo { DISPONIVEL, PERMISSAO_INSTALAR, ACESSO_PENDRIVE }

    private val mensagem: TextView = raiz.findViewById(R.id.mensagemAtualizacao)
    private val versoes: TextView = raiz.findViewById(R.id.versoesAtualizacao)
    private val primario: TextView = raiz.findViewById(R.id.botaoAtualizacaoPrimario)
    private val depois: TextView = raiz.findViewById(R.id.botaoAtualizacaoDepois)
    private val handler = Handler(Looper.getMainLooper())
    private val fecharPorInatividade = Runnable { adiar() }

    var modo: Modo? = null
        private set

    val visivel: Boolean get() = raiz.visibility == View.VISIBLE

    init {
        primario.setOnClickListener { modo?.let { m -> esconder(); aoConfirmar(m) } }
        depois.setOnClickListener { adiar() }
    }

    fun mostrar(modo: Modo, versaoAtual: String, versaoNova: String?) {
        this.modo = modo
        val c = raiz.context
        when (modo) {
            Modo.DISPONIVEL -> {
                mensagem.setText(R.string.atualizacao_nova)
                versoes.text = c.getString(R.string.atualizacao_versoes, versaoAtual, versaoNova ?: "?")
                versoes.visibility = View.VISIBLE
                primario.setText(R.string.atualizacao_agora)
                depois.setText(R.string.atualizacao_depois)
            }
            Modo.PERMISSAO_INSTALAR -> {
                mensagem.setText(R.string.atualizacao_permissao)
                versoes.text = c.getString(R.string.atualizacao_versoes, versaoAtual, versaoNova ?: "?")
                versoes.visibility = View.VISIBLE
                primario.setText(R.string.atualizacao_permitir)
                depois.setText(R.string.atualizacao_depois)
            }
            Modo.ACESSO_PENDRIVE -> {
                mensagem.setText(R.string.atualizacao_acesso)
                versoes.visibility = View.GONE
                primario.setText(R.string.atualizacao_permitir_acesso)
                depois.setText(R.string.atualizacao_agora_nao)
            }
        }
        raiz.visibility = View.VISIBLE
        primario.requestFocus()
        registrarAtividade()
    }

    /** "Depois" (botão, VOLTAR ou inatividade). */
    fun adiar() {
        val m = modo ?: return
        esconder()
        aoAdiar(m)
    }

    /** Some sem decidir nada (a Activity saiu da frente). */
    fun esconder() {
        handler.removeCallbacks(fecharPorInatividade)
        raiz.visibility = View.GONE
        modo = null
    }

    fun registrarAtividade() {
        handler.removeCallbacks(fecharPorInatividade)
        if (visivel) handler.postDelayed(fecharPorInatividade, INATIVIDADE_MS)
    }

    companion object {
        /** O mesmo do pedido de PIN: o anúncio por baixo continua contando (BUG-028). */
        const val INATIVIDADE_MS = 30_000L
    }
}
