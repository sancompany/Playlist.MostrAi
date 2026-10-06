package br.com.mostrai.player.atualizacao

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/** Um pendrive montado, como o `StorageManager` o descreve. */
data class VolumeUsb(
    /** UUID do sistema de arquivos — identifica o pendrive entre montagens. */
    val id: String,
    /** Caminho do volume, quando o Android o publica (não quer dizer que dê para ler). */
    val raiz: File?,
    /** Para o seletor do Android (Android 10+ abre direto no pendrive). */
    val volume: StorageVolume? = null,
)

fun interface FonteVolumes {
    fun volumes(): List<VolumeUsb>
}

/**
 * Pendrives montados agora, pela API pública (`StorageManager`, API 24+) —
 * nunca listando `/storage` às cegas nem API escondida. O caminho: no
 * Android 11+, `StorageVolume.getDirectory()`; antes, o ponto de montagem
 * que o próprio Android define para volume público (`/storage/<UUID>`).
 */
class VolumesAndroid(private val context: Context) : FonteVolumes {
    override fun volumes(): List<VolumeUsb> = runCatching {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        sm.storageVolumes
            .filter { it.isRemovable && !it.isPrimary }
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .mapNotNull { v ->
                val uuid = v.uuid ?: return@mapNotNull null
                VolumeUsb(id = uuid, raiz = caminho(v, uuid), volume = v)
            }
    }.getOrDefault(emptyList())

    private fun caminho(v: StorageVolume, uuid: String): File? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) v.directory else File("/storage", uuid)
}
