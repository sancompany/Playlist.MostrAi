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
import br.com.mostrai.player.config.ConfigAparelho

/**
 * Traz o player de volta se ele sumir da frente — crash, processo morto,
 * alguém apertou HOME. Sem Device Owner, sem lock task, sem se declarar
 * launcher (o instalador da TCL recusa o APK assim — ver
 * `docs/erros/2026-09-25-instalador-tcl-recusava-app-com-category-home.md`).
 *
 * Dois caminhos:
 * - **Retorno rápido** — a Activity saiu da frente (HOME, outro app por
 *   cima): um alarme exato a [ESPERAS_RETORNO_MS] traz o Player de volta em
 *   segundos, com novas tentativas se o launcher não deixar. Não reabre com
 *   a TV desligada (standby também tira a Activity da frente).
 * - **Backstop** — processo morto ou crash não passam pelo `onStop`: um
 *   alarme periódico confere o sinal de vida que a Activity renova e, se ele
 *   estiver velho, reabre o player.
 *
 * Sair do Player só pelo PIN: HOME não é interceptável por app nenhum (e se
 * declarar launcher o instalador da TCL recusa), então a resposta ao HOME é
 * voltar o mais rápido que o Android permite — ele segura aberturas em
 * segundo plano por 5 s depois do HOME, e esperar menos não adianta.
 *
 * **Saída autorizada.** Com o PIN de saída certo, a Activity chama
 * [autorizarSaida]: o watchdog para de reabrir. Abrir o app de novo (à mão
 * ou pelo boot) chama [rearmar] e tudo volta ao normal — uma saída
 * autorizada nunca vira uma TV apagada para sempre.
 *
 * **Antes da instalação, não puxa de volta.** Sem credencial não há anúncio
 * a proteger, e o instalador pode precisar sair do app para configurar
 * Wi-Fi ou a própria TV. O alarme continua agendado: assim que a tela é
 * provisionada, o watchdog vale normalmente.
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
    private const val CHAVE_NA_FRENTE = "na_frente"
    private const val CHAVE_TENTATIVA_RETORNO = "tentativa_retorno"
    private const val CHAVE_RETORNO_BLOQUEADO_EM = "retorno_bloqueado_em"

    const val ACAO_RETORNO = "br.com.mostrai.player.RETORNO_RAPIDO"

    const val INTERVALO_BASE_MS = 60_000L
    const val INTERVALO_MAXIMO_MS = 16 * 60_000L

    /** Quanto tempo sem sinal antes de considerar o player ausente (sinal a cada 30 s). */
    const val TOLERANCIA_MS = 90_000L

    /**
     * Retorno depois de sair da frente: a primeira no fim da trava de 5 s do
     * Android, as seguintes se o launcher ainda estiver na frente.
     */
    val ESPERAS_RETORNO_MS = listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L)

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

    /** A Activity está na frente: nada a trazer de volta. */
    fun naFrente(context: Context) {
        prefs(context).edit().putBoolean(CHAVE_NA_FRENTE, true).putInt(CHAVE_TENTATIVA_RETORNO, 0).commit()
        alarmes(context)?.let { a -> runCatching { a.cancel(pendingIntentRetorno(context)) } }
    }

    /**
     * A Activity saiu da frente. Se a tela está instalada e ninguém saiu pelo
     * PIN, agenda o retorno rápido. Gravado de forma síncrona: o alarme pode
     * disparar antes de um `apply` chegar ao disco.
     */
    fun saiuDaFrente(context: Context) {
        val p = prefs(context)
        p.edit().putBoolean(CHAVE_NA_FRENTE, false).putInt(CHAVE_TENTATIVA_RETORNO, 0).commit()
        if (p.getBoolean(CHAVE_SAIDA_AUTORIZADA, false) || !ConfigAparelho(context).provisionado) return
        agendarRetorno(context, ESPERAS_RETORNO_MS.first())
    }

    /**
     * PIN de saída correto. Gravado de forma síncrona antes de a Activity
     * fechar: um alarme que dispare no meio do caminho já encontra a saída
     * autorizada.
     */
    fun autorizarSaida(context: Context) {
        prefs(context).edit().putBoolean(CHAVE_SAIDA_AUTORIZADA, true).commit()
        cancelar(context)
        alarmes(context)?.let { a -> runCatching { a.cancel(pendingIntentRetorno(context)) } }
    }

    fun saidaAutorizada(context: Context): Boolean = prefs(context).getBoolean(CHAVE_SAIDA_AUTORIZADA, false)

    private fun alarmes(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * Exato até o Android 11 (`set` é inexato desde o API 19 e poderia
     * atrasar até 75% do prazo). Do 12 em diante, `setExact` exigiria
     * `SCHEDULE_EXACT_ALARM`: usa `set`, que o AlarmManager não adia abaixo
     * de 10 s — o primeiro retorno (5 s) continua no tempo
     * ([PoliticaDeRetorno.usaAlarmeExato]).
     */
    private fun agendarRetorno(context: Context, atrasoMs: Long) {
        val alarmes = alarmes(context) ?: return
        val quando = SystemClock.elapsedRealtime() + atrasoMs
        runCatching {
            if (PoliticaDeRetorno.usaAlarmeExato(Build.VERSION.SDK_INT)) {
                alarmes.setExact(AlarmManager.ELAPSED_REALTIME, quando, pendingIntentRetorno(context))
            } else {
                alarmes.set(AlarmManager.ELAPSED_REALTIME, quando, pendingIntentRetorno(context))
            }
        }.onFailure { Log.w(TAG, "não foi possível agendar o retorno rápido", it) }
    }

    fun agendar(context: Context, atrasoMs: Long = INTERVALO_BASE_MS) {
        val alarmes = alarmes(context) ?: return
        runCatching {
            alarmes.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + atrasoMs,
                pendingIntent(context),
            )
        }.onFailure { Log.w(TAG, "não foi possível agendar o watchdog", it) }
    }

    fun cancelar(context: Context) {
        val alarmes = alarmes(context) ?: return
        runCatching { alarmes.cancel(pendingIntent(context)) }
    }

    /**
     * Decide o que fazer agora. Pura de propósito, para caber em teste sem
     * `AlarmManager`: recebe o estado e devolve a decisão.
     */
    fun decidir(
        vivoEmMs: Long,
        agoraMs: Long,
        tentativas: Int,
        saidaAutorizada: Boolean = false,
        provisionado: Boolean = true,
    ): Decisao {
        if (saidaAutorizada) return Decisao(abrirPlayer = false, proximoAtrasoMs = null)
        if (!provisionado) return Decisao(abrirPlayer = false, proximoAtrasoMs = INTERVALO_BASE_MS)
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

    /**
     * Retorno rápido. Pura, para teste. Não reabre: com saída por PIN, sem
     * instalação, com o Player já na frente, ou com a TV desligada (o
     * backstop periódico traz o Player quando ela acender). `proximoAtrasoMs`
     * é a próxima tentativa caso esta não pegue.
     */
    fun decidirRetorno(
        saidaAutorizada: Boolean,
        provisionado: Boolean,
        naFrente: Boolean,
        telaLigada: Boolean,
        tentativa: Int,
    ): Decisao {
        if (saidaAutorizada || !provisionado || naFrente || !telaLigada) return Decisao(false, null)
        return Decisao(abrirPlayer = true, proximoAtrasoMs = ESPERAS_RETORNO_MS.getOrNull(tentativa + 1))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, Receptor::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context.applicationContext, 0, intent, flags)
    }

    /** Outro requestCode e outra ação: o retorno rápido não sobrescreve o backstop. */
    private fun pendingIntentRetorno(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, Receptor::class.java).setAction(ACAO_RETORNO)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context.applicationContext, 1, intent, flags)
    }

    private fun telaLigada(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

    /**
     * Abre a Activity. No Android 10+ sem "Exibir sobre outros apps" o
     * sistema bloqueia em silêncio ([PoliticaDeRetorno]): a tentativa fica,
     * e o bloqueio fica registrado para o bloco técnico.
     */
    fun abrirPlayer(context: Context) {
        if (!PoliticaDeRetorno.permitidoAgora(context)) {
            prefs(context).edit().putLong(CHAVE_RETORNO_BLOQUEADO_EM, System.currentTimeMillis()).apply()
        }
        val abrir = Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(abrir) }
            .onFailure { Log.w(TAG, "watchdog não conseguiu reabrir o player", it) }
    }

    /** Última vez que o sistema provavelmente bloqueou a volta do Player (relógio da TV), ou null. */
    fun retornoBloqueadoEm(context: Context): Long? =
        prefs(context).getLong(CHAVE_RETORNO_BLOQUEADO_EM, 0L).takeIf { it > 0L }

    class Receptor : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACAO_RETORNO) {
                retornar(context)
                return
            }
            val prefs = prefs(context)
            val tentativas = prefs.getInt(CHAVE_TENTATIVAS, 0)
            val decisao = decidir(
                vivoEmMs = prefs.getLong(CHAVE_VIVO_EM, 0L),
                agoraMs = SystemClock.elapsedRealtime(),
                tentativas = tentativas,
                saidaAutorizada = prefs.getBoolean(CHAVE_SAIDA_AUTORIZADA, false),
                provisionado = ConfigAparelho(context).provisionado,
            )

            // TV em standby (CPU acordada, tela apagada): reabrir aqui só faria
            // o Player subir e cair a cada alarme a noite inteira. Segue
            // vigiando no ritmo base; quando a tela acender, o próximo alarme
            // reabre.
            if (decisao.abrirPlayer && !telaLigada(context)) {
                agendar(context, INTERVALO_BASE_MS)
                return
            }
            if (decisao.abrirPlayer) {
                prefs.edit().putInt(CHAVE_TENTATIVAS, tentativas + 1).apply()
                abrirPlayer(context)
            }

            decisao.proximoAtrasoMs?.let { agendar(context, it) }
        }

        private fun retornar(context: Context) {
            val prefs = prefs(context)
            val tentativa = prefs.getInt(CHAVE_TENTATIVA_RETORNO, 0)
            val decisao = decidirRetorno(
                saidaAutorizada = prefs.getBoolean(CHAVE_SAIDA_AUTORIZADA, false),
                provisionado = ConfigAparelho(context).provisionado,
                naFrente = prefs.getBoolean(CHAVE_NA_FRENTE, false),
                telaLigada = telaLigada(context),
                tentativa = tentativa,
            )
            if (!decisao.abrirPlayer) return
            prefs.edit().putInt(CHAVE_TENTATIVA_RETORNO, tentativa + 1).commit()
            abrirPlayer(context)
            decisao.proximoAtrasoMs?.let { agendarRetorno(context, it) }
        }
    }
}
