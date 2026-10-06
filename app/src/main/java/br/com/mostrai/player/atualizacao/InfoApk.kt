package br.com.mostrai.player.atualizacao

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/** O que importa de um APK para decidir se ele é uma atualização legítima. */
data class InfoApk(
    val pacote: String,
    val versionCode: Long,
    val versionName: String?,
    /** SHA-256 (hex minúsculo) do certificado DER de cada assinante atual. */
    val certificados: Set<String>,
)

/** Lê pacote, versão e assinatura de um APK. `null` = o Android não reconhece o arquivo como APK. */
fun interface LeitorDeApk {
    fun ler(arquivo: File): InfoApk?
}

/**
 * Leitura pelo próprio `PackageManager` — o mesmo código que o instalador
 * usa para conferir a assinatura (v2/v3), sem parser próprio.
 *
 * API 26/27: `GET_SIGNATURES` (o `getPackageArchiveInfo` coleta os
 * certificados com essa flag). API 28+: `GET_SIGNING_CERTIFICATES`, e
 * `GET_SIGNATURES` junto como reserva — em algumas versões o `signingInfo`
 * de um arquivo (não instalado) vem vazio.
 */
class LeitorDeApkAndroid(private val context: Context) : LeitorDeApk {

    override fun ler(arquivo: File): InfoApk? = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(arquivo.absolutePath, FLAGS) ?: return null
        info.paraInfoApk()
    }.getOrNull()

    /** Este Player, instalado. */
    fun instalado(): InfoApk = context.packageManager.getPackageInfo(context.packageName, FLAGS).paraInfoApk()

    companion object {
        @Suppress("DEPRECATION")
        private val FLAGS: Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
            } else {
                PackageManager.GET_SIGNATURES
            }

        // O alerta de "certificados múltiplos" do lint não se aplica: a
        // comparação é do CONJUNTO inteiro de assinantes, e o instalador do
        // Android confere de novo antes de aceitar a atualização.
        @SuppressLint("PackageManagerGetSignatures")
        @Suppress("DEPRECATION")
        private fun PackageInfo.paraInfoApk(): InfoApk {
            val assinaturas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: signatures
            } else {
                signatures
            }
            return InfoApk(
                pacote = packageName,
                versionCode = PackageInfoCompat.getLongVersionCode(this),
                versionName = versionName,
                certificados = assinaturas.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet(),
            )
        }
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
