package br.com.mostrai.player.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import br.com.mostrai.player.BuildConfig
import br.com.mostrai.player.cache.ChaveCache
import br.com.mostrai.player.estado.DiarioBordo
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * OTA fase 1: baixa, verifica e **pede** a instalação.
 *
 * O limite é do Android, não do desenho: sem Device Owner, `PackageInstaller`
 * sempre mostra um diálogo de confirmação do sistema. Não existe instalação
 * silenciosa por aqui, e fingir que existe só produziria uma frota que não
 * atualiza. O que este código elimina é a viagem com o pendrive — troca
 * "levar um pendrive configurado até a loja" por "apertar OK no controle".
 *
 * Três guardas que não são opcionais:
 *
 * 1. **SHA-256 conferido antes de instalar.** Um APK não verificado
 *    instalado numa frota não se conserta remotamente depois.
 * 2. **Mesmo `applicationId`.** Um pacote diferente seria um app novo, e a
 *    identidade da tela e a fila de proof-of-play ficariam para trás.
 * 3. **`versionCode` maior.** O Android rejeita downgrade de qualquer forma;
 *    checar antes evita pedir instalação que vai falhar na cara do operador.
 *
 * A assinatura em si o Android valida sozinho no momento da instalação: um
 * APK assinado com outra chave é recusado como conflito de assinatura. Por
 * isso a chave de release precisa ser gerada **antes** da primeira instalação
 * definitiva — ver `RUNBOOK.md`.
 */
class Atualizador(
    context: Context,
    private val diario: DiarioBordo,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(ARQUIVO_PREFS, Context.MODE_PRIVATE)
    private val diretorio = File(app.cacheDir, "update").apply { mkdirs() }

    var estado: EstadoUpdate
        get() = runCatching { EstadoUpdate.valueOf(prefs.getString(CHAVE_ESTADO, null) ?: "") }
            .getOrDefault(EstadoUpdate.NONE)
        private set(valor) = prefs.edit().putString(CHAVE_ESTADO, valor.name).apply()

    var buildAlvo: Int
        get() = prefs.getInt(CHAVE_BUILD_ALVO, 0)
        private set(valor) = prefs.edit().putInt(CHAVE_BUILD_ALVO, valor).apply()

    var versaoAlvo: String?
        get() = prefs.getString(CHAVE_VERSAO_ALVO, null)
        private set(valor) = prefs.edit().putString(CHAVE_VERSAO_ALVO, valor).apply()

    var obrigatorio: Boolean
        get() = prefs.getBoolean(CHAVE_OBRIGATORIO, false)
        private set(valor) = prefs.edit().putBoolean(CHAVE_OBRIGATORIO, valor).apply()

    private var proximaTentativaMs: Long
        get() = prefs.getLong(CHAVE_PROXIMA_TENTATIVA, 0L)
        set(valor) = prefs.edit().putLong(CHAVE_PROXIMA_TENTATIVA, valor).apply()

    fun arquivoPronto(): File? = File(diretorio, "$buildAlvo.apk").takeIf {
        estado == EstadoUpdate.READY && it.exists() && it.length() > 0
    }

    /**
     * Registra o manifesto recebido no heartbeat. Não baixa nada — quem
     * decide quando gastar banda é [baixarSeNecessario], numa thread de
     * fundo.
     */
    @Synchronized
    fun considerar(manifesto: UpdateManifesto?) {
        if (manifesto == null) {
            if (estado != EstadoUpdate.NONE && estado != EstadoUpdate.INSTALL_REQUESTED) limpar()
            return
        }
        if (manifesto.build <= BuildConfig.VERSION_CODE) {
            // Já estamos nesta versão ou à frente: se havia update pendente
            // para este build, ele foi instalado.
            if (buildAlvo in 1..manifesto.build) {
                diario.registrar(DiarioBordo.Codigo.UPDATE_BAIXADO, "atualizado para ${BuildConfig.VERSION_NAME}")
                limpar()
            }
            return
        }
        if (manifesto.build == buildAlvo && estado in setOf(EstadoUpdate.READY, EstadoUpdate.DOWNLOADING)) return

        buildAlvo = manifesto.build
        versaoAlvo = manifesto.versao
        obrigatorio = manifesto.obrigatorio
        estado = EstadoUpdate.AVAILABLE
        diario.registrar(DiarioBordo.Codigo.UPDATE_DETECTADO, "build ${manifesto.build} (${manifesto.versao})")
    }

    /** Bloqueante — chamar de thread de fundo. */
    @Synchronized
    fun baixarSeNecessario(manifesto: UpdateManifesto?) {
        if (manifesto == null || estado != EstadoUpdate.AVAILABLE) return
        if (manifesto.build != buildAlvo) return

        val destino = File(diretorio, "${manifesto.build}.apk")
        estado = EstadoUpdate.DOWNLOADING
        try {
            baixar(manifesto, destino)
            if (!pacoteConfere(destino, manifesto.build)) {
                throw IOException("APK não é uma atualização de ${BuildConfig.APPLICATION_ID}")
            }
            estado = EstadoUpdate.READY
            diario.registrar(DiarioBordo.Codigo.UPDATE_BAIXADO, "build ${manifesto.build} verificado")
        } catch (e: Exception) {
            destino.delete()
            estado = EstadoUpdate.FAILED
            diario.registrar(DiarioBordo.Codigo.UPDATE_FALHOU, e.message ?: "falha ao baixar")
            Log.w(TAG, "falha ao preparar atualização", e)
        }
    }

    @Throws(IOException::class)
    private fun baixar(manifesto: UpdateManifesto, destino: File) {
        val temporario = File(diretorio, "${manifesto.build}.apk.tmp")
        val conexao = try {
            URL(manifesto.url).openConnection() as HttpURLConnection
        } catch (e: ClassCastException) {
            throw IOException("URL de atualização não é http(s)", e)
        }
        try {
            conexao.connectTimeout = TIMEOUT_CONEXAO_MS
            conexao.readTimeout = TIMEOUT_LEITURA_MS
            conexao.connect()
            if (conexao.responseCode !in 200..299) throw IOException("HTTP ${conexao.responseCode}")

            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(conexao.inputStream, digest).use { entrada ->
                temporario.outputStream().use { saida -> entrada.copyTo(saida) }
            }

            val obtido = ChaveCache.paraHex(digest.digest())
            if (obtido != manifesto.sha256) {
                throw IOException("SHA-256 divergente: esperado ${manifesto.sha256}, obtido $obtido")
            }
            if (!temporario.renameTo(destino)) throw IOException("não foi possível mover o APK baixado")
        } finally {
            conexao.disconnect()
            temporario.delete()
        }
    }

    /** O APK baixado precisa ser deste mesmo app, e mais novo. */
    private fun pacoteConfere(apk: File, buildEsperado: Int): Boolean {
        val info = app.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
        if (info.packageName != BuildConfig.APPLICATION_ID) return false
        val codigo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
        return codigo == buildEsperado && codigo > BuildConfig.VERSION_CODE
    }

    /**
     * Pode pedir instalação agora? Duas condições, e as duas existem para
     * não transformar a atualização num incômodo: só fora de exibição paga
     * (quem chama garante isso) e respeitando o backoff de um cancelamento
     * anterior.
     */
    fun podePedirInstalacao(agoraMs: Long = System.currentTimeMillis()): Boolean =
        estado == EstadoUpdate.READY && agoraMs >= proximaTentativaMs

    /**
     * Abre o diálogo do sistema. Sem Device Owner isso é o máximo que o
     * Android permite; o retorno é assíncrono e chega em [aoResultadoInstalacao].
     */
    @Synchronized
    fun pedirInstalacao(): Boolean {
        val apk = arquivoPronto() ?: return false
        if (!app.packageManager.canRequestPackageInstallsCompat()) {
            diario.registrar(
                DiarioBordo.Codigo.UPDATE_FALHOU,
                "permissão de instalar apps desconhecidos não concedida",
            )
            return false
        }
        return try {
            val instalador = app.packageManager.packageInstaller
            val parametros = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val idSessao = instalador.createSession(parametros)
            instalador.openSession(idSessao).use { sessao ->
                apk.inputStream().use { entrada ->
                    sessao.openWrite("base.apk", 0, apk.length()).use { saida ->
                        entrada.copyTo(saida)
                        sessao.fsync(saida)
                    }
                }
                val intent = Intent(app, ReceptorInstalacao::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(app, idSessao, intent, flags)
                sessao.commit(pending.intentSender)
            }
            estado = EstadoUpdate.INSTALL_REQUESTED
            diario.registrar(DiarioBordo.Codigo.UPDATE_INSTALACAO_PEDIDA, "build $buildAlvo")
            true
        } catch (e: Exception) {
            estado = EstadoUpdate.FAILED
            diario.registrar(DiarioBordo.Codigo.UPDATE_FALHOU, e.message ?: "falha ao abrir instalação")
            Log.w(TAG, "falha ao pedir instalação", e)
            false
        }
    }

    /**
     * O operador fechou o diálogo. Volta a READY, mas com uma janela de
     * silêncio — insistir a cada ciclo transformaria a TV da loja num
     * pop-up piscando, que é pior que não atualizar.
     */
    @Synchronized
    fun adiar(horas: Int) {
        if (estado == EstadoUpdate.INSTALL_REQUESTED || estado == EstadoUpdate.READY) {
            estado = EstadoUpdate.DEFERRED
            proximaTentativaMs = System.currentTimeMillis() + horas * 60L * 60L * 1000L
        }
    }

    /** Sai de DEFERRED quando a janela de silêncio passa. */
    @Synchronized
    fun reavaliarAdiamento(agoraMs: Long = System.currentTimeMillis()) {
        if (estado == EstadoUpdate.DEFERRED && agoraMs >= proximaTentativaMs && arquivoExiste()) {
            estado = EstadoUpdate.READY
        }
    }

    private fun arquivoExiste(): Boolean = File(diretorio, "$buildAlvo.apk").exists()

    @Synchronized
    fun limpar() {
        diretorio.listFiles()?.forEach { it.delete() }
        prefs.edit().clear().apply()
    }

    private fun PackageManager.canRequestPackageInstallsCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) canRequestPackageInstalls() else true

    companion object {
        private const val TAG = "Atualizador"
        const val ARQUIVO_PREFS = "mostrai_update"
        private const val CHAVE_ESTADO = "estado"
        private const val CHAVE_BUILD_ALVO = "build_alvo"
        private const val CHAVE_VERSAO_ALVO = "versao_alvo"
        private const val CHAVE_OBRIGATORIO = "obrigatorio"
        private const val CHAVE_PROXIMA_TENTATIVA = "proxima_tentativa_ms"
        private const val TIMEOUT_CONEXAO_MS = 20_000
        private const val TIMEOUT_LEITURA_MS = 60_000
    }
}
