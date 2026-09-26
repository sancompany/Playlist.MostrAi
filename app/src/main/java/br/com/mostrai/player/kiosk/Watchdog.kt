package br.com.mostrai.player.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import br.com.mostrai.player.PlayerActivity

/**
 * Traz o player de volta se ele sumir da frente — crash, processo morto,
 * alguém apertou HOME. Sem Device Owner, sem lock task, sem se declarar
 * launcher (o instalador da TCL recusa o APK assim — ver
 * `docs/erros/2026-09-25-instalador-tcl-recusava-app-com-category-home.md`).
 *
 * Um alarme periódico confere um sinal de vida que a Activity renova e, se
 * ele estiver velho, reabre o player.
 *
 * **Saída autorizada.** Com o PIN de saída certo, a Activity chama
 * [autorizarSaida]: o watchdog para de reabrir. Abrir o app de novo (à mão
 * ou pelo boot) chama [rearmar] e tudo volta ao normal — uma saída
 * autorizada nunca vira uma TV apagada para sempre.
 *
 * **Sem laço de crash.** Cada reabertura consecutiva sem sinal de vida dobra
 * o intervalo, até [INTERVALO_MAXIMO_MS]; o contador zera assim que a
 * Activity volta a dar sinal.
 */
object Watchdog {

    private const val TAG = "Watchdog"
    private const val ARQUIVO = "mostrai_watchdog"
    private const val CHAVE_VIVO_EM = "vivo_em"
    private const val CHAVE_TENTATIVAS = "tentativas"
    private const val CHAVE_SAIDA_AUTORIZADA = "saida_autorizada"

    const val INTERVALO_BASE_MS = 2 * 60_000L
    const val INTERVALO_MAXIMO_MS = 32 * 60_000L

    /** Quanto tempo sem sinal antes de considerar o player ausente. */
    const val TOLERANCIA_MS = 5 * 60_000L

    fun registrarSinalDeVida(context: Context) {
        prefs(context).edit()
            .putLong(CHAVE_VIVO_EM, SystemClock.elapsedRealtime())
            .putInt(CHAVE_TENTATIVAS, 0)
            .apply()
    }

    /** O player voltou à frente (abertura manual, boot): operação normal. */
    fun rearmar(context: Context) {
        prefs(context).edit().putBoolean(CHAVE_SAIDA_AUTORIZADA, false).commit()
        agendar(context)
    }

    /**
     * PIN de saída correto. Gravado de forma síncrona antes de a Activity
     * fechar: um alarme que dispare no meio do caminho já encontra a saída
     * autorizada.
     */
    fun autorizarSaida(context: Context) {
        prefs(context).edit().putBoolean(CHAVE_SAIDA_AUTORIZADA, true).commit()
        cancelar(context)
    }

    fun saidaAutorizada(context: Context): Boolean = prefs(context).getBoolean(CHAVE_SAIDA_AUTORIZADA, false)

    fun agendar(context: Context, atrasoMs: Long = INTERVALO_BASE_MS) {
        val alarmes = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            alarmes.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + atrasoMs,
                pendingIntent(context),
            )
        }.onFailure { Log.w(TAG, "não foi possível agendar o watchdog", it) }
    }

    fun cancelar(context: Context) {
        val alarmes = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { alarmes.cancel(pendingIntent(context)) }
    }

    /**
     * Decide o que fazer agora. Pura de propósito, para caber em teste sem
     * `AlarmManager`: recebe o estado e devolve a decisão.
     */
    fun decidir(vivoEmMs: Long, agoraMs: Long, tentativas: Int, saidaAutorizada: Boolean = false): Decisao {
        if (saidaAutorizada) return Decisao(abrirPlayer = false, proximoAtrasoMs = null)
        val silencioso = vivoEmMs <= 0L || agoraMs - vivoEmMs > TOLERANCIA_MS
        // O relógio monotônico zera no reboot: um "vivoEm" no futuro é
        // resquício do boot anterior, não sinal de vida desta sessão.
        val aposReboot = vivoEmMs > agoraMs
        val precisaAbrir = silencioso || aposReboot
        val proximoAtraso = if (precisaAbrir) {
            (INTERVALO_BASE_MS shl tentativas.coerceIn(0, 4)).coerceAtMost(INTERVALO_MAXIMO_MS)
        } else {
            INTERVALO_BASE_MS
        }
        return Decisao(abrirPlayer = precisaAbrir, proximoAtrasoMs = proximoAtraso)
    }

    /** `proximoAtrasoMs` null = não reagendar (saída autorizada). */
    data class Decisao(val abrirPlayer: Boolean, val proximoAtrasoMs: Long?)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, Receptor::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context.applicationContext, 0, intent, flags)
    }

    class Receptor : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prefs = prefs(context)
            val tentativas = prefs.getInt(CHAVE_TENTATIVAS, 0)
            val decisao = decidir(
                vivoEmMs = prefs.getLong(CHAVE_VIVO_EM, 0L),
                agoraMs = SystemClock.elapsedRealtime(),
                tentativas = tentativas,
                saidaAutorizada = prefs.getBoolean(CHAVE_SAIDA_AUTORIZADA, false),
            )

            if (decisao.abrirPlayer) {
                prefs.edit().putInt(CHAVE_TENTATIVAS, tentativas + 1).apply()
                val abrir = Intent(context, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(abrir) }
                    .onFailure { Log.w(TAG, "watchdog não conseguiu reabrir o player", it) }
            }

            decisao.proximoAtrasoMs?.let { agendar(context, it) }
        }
    }
}
