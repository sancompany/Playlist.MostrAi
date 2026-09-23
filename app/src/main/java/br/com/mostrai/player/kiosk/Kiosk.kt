package br.com.mostrai.player.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log

/**
 * O que dá para endurecer sem exigir hardware ou provisionamento especial.
 *
 * **Device Owner é opcional por decisão.** Há relato documentado de
 * `dpm set-device-owner` falhar em TV Android da TCL — o fabricante do parque
 * instalado — e exigir isso transformaria a primeira instalação num projeto
 * de infraestrutura. Então o player detecta, usa o que ganhar, e funciona
 * igual quando não ganha nada: tela cheia, immersive, auto-start, launcher
 * padrão (se o operador escolher) e watchdog.
 */
object Kiosk {

    fun ehDeviceOwner(context: Context): Boolean = runCatching {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        dpm?.isDeviceOwnerApp(context.packageName) == true
    }.getOrDefault(false)

    /**
     * Lock task de verdade só existe com Device Owner. Sem ele,
     * `startLockTask` abriria o diálogo "Fixar tela?" — pedir confirmação
     * para entrar em modo quiosque numa TV sem ninguém na frente é pior que
     * não tentar, então simplesmente não tentamos.
     */
    fun ativarLockTaskSePossivel(activity: Activity): Boolean {
        if (!ehDeviceOwner(activity)) return false
        return runCatching {
            val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.setLockTaskPackages(
                ComponenteAdmin.nome(activity),
                arrayOf(activity.packageName),
            )
            activity.startLockTask()
            true
        }.onFailure { Log.w(TAG, "lock task indisponível", it) }.getOrDefault(false)
    }

    private const val TAG = "Kiosk"
}

/**
 * Placeholder do componente de administração.
 *
 * O app ainda não declara um `DeviceAdminReceiver` — declarar um sem que
 * ninguém possa inscrevê-lo só adicionaria superfície. Quando o teste em
 * aparelho real confirmar que este modelo aceita Device Owner, este objeto
 * passa a apontar para o receiver de verdade; até lá,
 * [Kiosk.ativarLockTaskSePossivel] falha de forma controlada.
 */
internal object ComponenteAdmin {
    fun nome(context: Context): android.content.ComponentName =
        android.content.ComponentName(context, "${context.packageName}.AdminReceiver")
}
