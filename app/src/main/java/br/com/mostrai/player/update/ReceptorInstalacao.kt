package br.com.mostrai.player.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import br.com.mostrai.player.estado.DiarioBordo

/**
 * Resultado do diálogo de instalação do sistema.
 *
 * `STATUS_PENDING_USER_ACTION` chega quando o Android quer que **nós**
 * abramos a tela de confirmação. Sem Device Owner isso é o caminho normal,
 * não um erro — e é o único momento em que a Activity de confirmação pode ser
 * lançada legitimamente de um receiver.
 */
class ReceptorInstalacao : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val diario = DiarioBordo(context)
        val atualizador = Atualizador(context, diario)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmacao = intent.extraIntentCompat() ?: return
                confirmacao.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirmacao) }
                    .onFailure { Log.w(TAG, "não foi possível abrir a confirmação de instalação", it) }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                diario.registrar(DiarioBordo.Codigo.UPDATE_BAIXADO, "instalação concluída")
                atualizador.limpar()
            }

            // O operador fechou o diálogo, ou o sistema abortou. Não é falha
            // permanente: volta para a fila com janela de silêncio.
            else -> {
                val mensagem = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                diario.registrar(DiarioBordo.Codigo.UPDATE_FALHOU, "status $status: ${mensagem ?: "sem detalhe"}")
                atualizador.adiar(HORAS_APOS_CANCELAMENTO)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.extraIntentCompat(): Intent? =
        getParcelableExtra(Intent.EXTRA_INTENT) as? Intent

    private companion object {
        const val TAG = "ReceptorInstalacao"
        const val HORAS_APOS_CANCELAMENTO = 6
    }
}
