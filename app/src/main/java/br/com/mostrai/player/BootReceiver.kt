package br.com.mostrai.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Sobe o player quando a TV liga.
 *
 * Deliberadamente simples: o ciclo de vida completo (serviço em foreground,
 * recuperação depois de o sistema matar o processo) ainda está em aberto no
 * desenho do projeto.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val acao = intent.action ?: return
        if (acao != Intent.ACTION_BOOT_COMPLETED && acao != ACAO_QUICKBOOT) return

        val abrir = Intent(context, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(abrir)
    }

    private companion object {
        /** Alguns aparelhos usam este broadcast em vez do BOOT_COMPLETED. */
        const val ACAO_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
