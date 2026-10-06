package br.com.mostrai.player.kiosk

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * O que o Android de cada versão deixa o Player fazer para voltar sozinho à
 * frente (boot, HOME, crash) — sem se declarar launcher (`HOME`, que o
 * instalador da TCL recusa) e sem Device Owner.
 *
 * - **Até o Android 9 (API 28)** — a TCL 32S6500S: abrir a Activity do
 *   segundo plano é permitido.
 * - **Android 10+ (API 29+)**: o sistema bloqueia, em silêncio, Activity
 *   aberta do segundo plano, qualquer que seja o target. A única exceção
 *   oficial ao alcance de um app instalado por pendrive é a permissão
 *   especial "Exibir sobre outros apps" (`SYSTEM_ALERT_WINDOW`), concedida
 *   na TV (Configurações › Apps › Acesso especial) ou por
 *   `adb shell appops set br.com.mostrai.player SYSTEM_ALERT_WINDOW allow`.
 *   Sem ela, o Player não volta sozinho — e o bloco técnico diz isso.
 *
 * Alarme exato: até o Android 11 (API 30) não pede permissão. Do 12 em
 * diante, com target 31+, `setExact` exige `SCHEDULE_EXACT_ALARM` — que não
 * pedimos: `set` com menos de 10 s não é adiado pelo AlarmManager, e é o
 * caso do primeiro retorno (5 s). Puro, para teste.
 */
object PoliticaDeRetorno {

    fun podeAbrirDoFundo(sdk: Int, sobreposicaoPermitida: Boolean): Boolean =
        sdk < Build.VERSION_CODES.Q || sobreposicaoPermitida

    fun usaAlarmeExato(sdk: Int): Boolean = sdk < Build.VERSION_CODES.S

    /** No aparelho de agora. */
    fun permitidoAgora(context: Context): Boolean =
        podeAbrirDoFundo(Build.VERSION.SDK_INT, runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false))
}
