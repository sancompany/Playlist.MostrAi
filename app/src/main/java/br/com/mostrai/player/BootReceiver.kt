package br.com.mostrai.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import br.com.mostrai.player.kiosk.Watchdog

/**
 * Sobe o player quando a TV liga — inclusive depois de uma saída autorizada
 * por PIN: o reboot devolve a TV à operação normal ([Watchdog.rearmar]).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val acao = intent.action ?: return
        if (acao != Intent.ACTION_BOOT_COMPLETED && acao != ACAO_QUICKBOOT) return
        // BOOT_COMPLETED só o sistema envia; QUICKBOOT_POWERON qualquer app
        // pode forjar — e ele desfaz a saída autorizada por PIN. Um quick-boot
        // de verdade chega com o aparelho recém-ligado.
        if (acao == ACAO_QUICKBOOT && SystemClock.elapsedRealtime() > LIMITE_QUICKBOOT_MS) return

        // ROB-009: alarme não sobrevive a reboot, e só o PlayerActivity
        // reagendava o watchdog. Se a abertura abaixo não pegar (firmware
        // atrasando ou recusando), sem isto nada tentaria de novo.
        Watchdog.rearmar(context)

        val abrir = Intent(context, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(abrir) }
            .onFailure { Log.w("BootReceiver", "não abriu o player no boot; o watchdog tenta de novo", it) }
    }

    private companion object {
        /** Alguns aparelhos usam este broadcast em vez do BOOT_COMPLETED. */
        const val ACAO_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"

        /** Uptime acima disto: não é boot, é alguém mandando o broadcast. */
        const val LIMITE_QUICKBOOT_MS = 10 * 60_000L
    }
}
