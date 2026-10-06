package br.com.mostrai.player.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import br.com.mostrai.player.PlayerActivity
import br.com.mostrai.player.estado.DiarioBordo

/**
 * **Teste** de "ligar a tela" — capacidade a provar na TV, não recurso em
 * uso. A intenção futura é: horário de funcionamento começa → o Player
 * acende o painel → volta à frente → sincroniza. Isso só existe se o
 * hardware deixar, e nada garante que deixe:
 *
 * - TV **sem energia** não liga por app nenhum — fora de questão;
 * - TV em **standby com o SoC dormindo** (sem "Ligar instantâneo"): o alarme
 *   `*_WAKEUP` só dispara se o firmware mantiver o relógio de despertar;
 * - TV em standby com o SoC ativo: o caminho oficial do Android é uma
 *   wake lock com `ACQUIRE_CAUSES_WAKEUP` + Activity com "turn screen on" —
 *   se o painel acende de verdade, só o aparelho diz.
 *
 * Por isso só o técnico dispara, pelo bloco técnico (agenda para daqui a 2
 * min, põe a TV em standby e observa). O watchdog continua sem acordar TV
 * em standby; o horário de funcionamento continua sem acender o painel.
 * Ligar automático só depois de o teste físico passar na TCL (pendência).
 */
object LigarTela {

    private const val TAG = "LigarTela"
    private const val ARQUIVO = "mostrai_ligar_tela"
    private const val CHAVE_AGENDADO_PARA = "agendado_para"
    private const val CHAVE_ULTIMO = "ultimo_resultado"

    const val ACAO = "br.com.mostrai.player.LIGAR_TELA_TESTE"
    const val EXTRA_ACORDAR = "br.com.mostrai.player.ACORDAR_TELA"
    const val ESPERA_TESTE_MS = 2 * 60_000L
    private const val WAKE_LOCK_MS = 15_000L

    fun agendarTeste(context: Context, atrasoMs: Long = ESPERA_TESTE_MS) {
        val alarmes = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val quando = SystemClock.elapsedRealtime() + atrasoMs
        runCatching {
            // Sem permissão de alarme exato: no 12+ `set` (pode atrasar um
            // pouco — é teste, não horário de anúncio).
            if (PoliticaDeRetorno.usaAlarmeExato(Build.VERSION.SDK_INT)) {
                alarmes.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, quando, pendingIntent(context))
            } else {
                alarmes.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, quando, pendingIntent(context))
            }
            prefs(context).edit().putLong(CHAVE_AGENDADO_PARA, System.currentTimeMillis() + atrasoMs).apply()
        }.onFailure { Log.w(TAG, "não foi possível agendar o teste de ligar a tela", it) }
    }

    /** Para o bloco técnico: "agendado para …", ou o que o último disparo registrou. */
    fun situacao(context: Context): String? {
        val p = prefs(context)
        val agendado = p.getLong(CHAVE_AGENDADO_PARA, 0L)
        if (agendado > System.currentTimeMillis()) return "teste agendado"
        return p.getString(CHAVE_ULTIMO, null)
    }

    /** Chamado pela Activity aberta pelo teste: o que o Android diz da tela agora. */
    fun registrarResultado(context: Context, telaLigada: Boolean) {
        val texto = if (telaLigada) "Android informou tela ligada após o teste" else "Android informou tela ainda desligada após o teste"
        prefs(context).edit().remove(CHAVE_AGENDADO_PARA).putString(CHAVE_ULTIMO, texto).apply()
        runCatching { DiarioBordo(context).registrar(DiarioBordo.Codigo.LIGAR_TELA_TESTE, texto) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, Receptor::class.java).setAction(ACAO)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context.applicationContext, 2, intent, flags)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    class Receptor : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACAO) return
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            // Caminho oficial para acender a tela a partir de um app: wake lock
            // de tela com ACQUIRE_CAUSES_WAKEUP, por poucos segundos — a
            // Activity, com "turn screen on", mantém a tela acesa depois.
            @Suppress("DEPRECATION")
            runCatching {
                pm?.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "mostrai:ligar-tela-teste",
                )?.acquire(WAKE_LOCK_MS)
            }.onFailure { Log.w(TAG, "wake lock recusada", it) }
            val abrir = Intent(context, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_ACORDAR, true)
            runCatching { context.startActivity(abrir) }.onFailure { Log.w(TAG, "não abriu o Player", it) }
        }
    }
}
