package br.com.mostrai.player.atualizacao

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Quem instala é o Android: o Player prepara e valida, e entrega a cópia
 * privada ao instalador oficial do sistema, que pede a confirmação. Nada de
 * instalação silenciosa, root, `adb` ou Device Owner.
 *
 * A cópia vai por `FileProvider` (`content://`), nunca por `file://`, com
 * permissão de leitura só para quem recebe o Intent. Cancelar ou falhar no
 * instalador deixa a versão atual intacta — nada é desinstalado antes.
 */
object InstaladorApk {

    const val MIME = "application/vnd.android.package-archive"

    fun autoridade(context: Context): String = "${context.packageName}.atualizacao"

    /** "Instalar apps desconhecidos" concedido ao Mostraí Player (Android 8+: por app). */
    fun podeInstalar(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    fun uri(context: Context, arquivo: File): Uri = FileProvider.getUriForFile(context, autoridade(context), arquivo)

    fun intentInstalar(context: Context, arquivo: File): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, arquivo), MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /**
     * Onde o técnico libera "instalar apps desconhecidos" para o Mostraí, do
     * mais direto ao mais genérico — cada firmware de TV traz um conjunto
     * diferente de telas. Tentar na ordem até um abrir.
     */
    /**
     * Tenta cada Intent até uma abrir. Firmware de TV pode não ter a tela
     * (`ActivityNotFoundException`) ou resolvê-la para uma Activity que não
     * deixa abrir (`SecurityException`, Android 8–12): nenhuma das duas pode
     * derrubar o Player. `false` = nenhuma abriu.
     */
    fun abrirPrimeira(intents: List<Intent>, abrir: (Intent) -> Unit): Boolean {
        for (intent in intents) {
            try {
                abrir(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // próxima
            } catch (_: SecurityException) {
                // próxima
            }
        }
        return false
    }

    fun intentsPermissao(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
        Intent(Settings.ACTION_SECURITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
}
