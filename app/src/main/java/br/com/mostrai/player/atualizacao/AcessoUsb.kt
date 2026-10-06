package br.com.mostrai.player.atualizacao

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Como o Player consegue ler um pendrive, do mais automático ao que pede
 * uma ação do técnico — sem "Acesso a todos os arquivos":
 *
 * 1. **Direto** (Android 8/9 — a TCL — e Android 10): leitura do arquivo
 *    com `READ_EXTERNAL_STORAGE`, concedida uma vez. Só funciona se o
 *    firmware expõe o pendrive aos apps (o caminho `/storage/<UUID>` lê);
 *    a TCL ainda não foi provada (teste físico).
 * 2. **Seletor do Android** (Storage Access Framework): o técnico autoriza a
 *    pasta do pendrive uma vez, a permissão fica guardada por pendrive. É o
 *    caminho do Android 11+ e a reserva se o firmware esconder o USB.
 * 3. Nenhum dos dois: o bloco técnico diz por quê.
 */
object AcessoUsb {

    sealed class Acesso {
        class Direto(val raiz: File) : Acesso()
        class Documento(val arvore: Uri) : Acesso()
        /** Android ≤ 10 sem a permissão de leitura: pedir. */
        object PrecisaPermissaoLeitura : Acesso()
        /** Precisa o técnico autorizar a pasta no seletor do Android. */
        object PrecisaSeletor : Acesso()
        /** Nem leitura direta nem seletor neste aparelho. */
        object Indisponivel : Acesso()
    }

    private const val ARQUIVO = "mostrai_atualizacao_usb"
    private const val PREFIXO_ARVORE = "arvore_"
    private const val PREFIXO_RECUSA = "recusa_"

    /** Leitura direta só faz sentido até o Android 10 (no 11+ o arquivo APK não é mídia). */
    fun leituraDiretaPossivel(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk <= Build.VERSION_CODES.Q

    fun temPermissaoLeitura(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun decidir(
        volume: VolumeUsb,
        sdk: Int,
        permissaoLeitura: Boolean,
        raizLegivel: (File) -> Boolean,
        arvoreGuardada: Uri?,
        seletorDisponivel: Boolean,
    ): Acesso {
        val raiz = volume.raiz
        if (leituraDiretaPossivel(sdk) && permissaoLeitura && raiz != null && raizLegivel(raiz)) return Acesso.Direto(raiz)
        if (arvoreGuardada != null) return Acesso.Documento(arvoreGuardada)
        if (leituraDiretaPossivel(sdk) && !permissaoLeitura) return Acesso.PrecisaPermissaoLeitura
        return if (seletorDisponivel) Acesso.PrecisaSeletor else Acesso.Indisponivel
    }

    fun decidir(context: Context, volume: VolumeUsb): Acesso = decidir(
        volume = volume,
        sdk = Build.VERSION.SDK_INT,
        permissaoLeitura = temPermissaoLeitura(context),
        raizLegivel = { runCatching { it.list() != null }.getOrDefault(false) },
        arvoreGuardada = arvoreGuardada(context, volume.id),
        seletorDisponivel = seletorDisponivel(context),
    )

    /** O seletor de pastas do Android existe neste aparelho? (Muitas TVs não trazem.) */
    fun seletorDisponivel(context: Context): Boolean =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(context.packageManager) != null

    /** Intent do seletor, aberto direto no pendrive quando o Android deixa (10+). */
    fun intentSeletor(volume: VolumeUsb): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && volume.volume != null) {
            volume.volume.createOpenDocumentTreeIntent()
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        }

    /** Guarda a pasta autorizada para este pendrive (a permissão do Android persiste). */
    fun guardarArvore(context: Context, volumeId: String, arvore: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(arvore, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs(context).edit().putString(PREFIXO_ARVORE + volumeId, arvore.toString()).apply()
    }

    fun arvoreGuardada(context: Context, volumeId: String): Uri? {
        val uri = prefs(context).getString(PREFIXO_ARVORE + volumeId, null)?.let(Uri::parse) ?: return null
        val aindaVale = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return uri.takeIf { aindaVale }
    }

    /**
     * "Agora não" ao pedido de acesso: um pendrive que fica espetado na TV
     * (de outro uso) não volta a cobrir o anúncio a cada reboot. Vale até
     * ele ser visto fora ([esquecerRecusasAusentes]) ou o técnico apertar
     * Verificar USB ([esquecerRecusas]).
     */
    fun recusarAcesso(context: Context, volumeId: String) {
        prefs(context).edit().putBoolean(PREFIXO_RECUSA + volumeId, true).apply()
    }

    fun acessoRecusado(context: Context, volumeId: String): Boolean =
        prefs(context).getBoolean(PREFIXO_RECUSA + volumeId, false)

    fun esquecerRecusasAusentes(context: Context, montados: Set<String>) {
        val p = prefs(context)
        val ausentes = p.all.keys.filter { it.startsWith(PREFIXO_RECUSA) && it.removePrefix(PREFIXO_RECUSA) !in montados }
        if (ausentes.isNotEmpty()) p.edit().apply { ausentes.forEach(::remove) }.apply()
    }

    fun esquecerRecusas(context: Context) = esquecerRecusasAusentes(context, emptySet())

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)
}
