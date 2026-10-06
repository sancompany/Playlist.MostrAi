package br.com.mostrai.player.atualizacao

import java.io.File

/**
 * O Robolectric não lê assinatura de APK de verdade: nos testes um "APK" é um
 * texto `APK|pacote|versionCode|versionName|cert1,cert2`, lido por
 * [LeitorFalso]. Qualquer outro conteúdo é "não é APK" (`null`), como o
 * `PackageManager` responderia.
 */
object ApkFalso {
    const val PACOTE = "br.com.mostrai.player"
    val OFICIAL = "8c4e" + "a".repeat(60)
    val OUTRA = "b".repeat(64)

    val instalado = InfoApk(PACOTE, 6, "3.0.1", setOf(OFICIAL))

    fun bytes(
        pacote: String = PACOTE,
        versionCode: Long = 7,
        versionName: String = "3.0.2",
        certificados: Set<String> = setOf(OFICIAL),
    ): ByteArray = "APK|$pacote|$versionCode|$versionName|${certificados.joinToString(",")}".toByteArray()

    fun json(
        apk: ByteArray,
        versionCode: Long = 7,
        certificado: String = OFICIAL,
        sha256: String = sha256Hex(apk),
    ): String = """{"versionName":"3.0.2","versionCode":$versionCode,"sha256":"$sha256","certificateSha256":"$certificado"}"""

    /** Grava o pacote oficial (`MOSTRAI/update/`) num "pendrive". */
    fun gravarOficial(pendrive: File, apk: ByteArray = bytes(), json: String? = json(apk)) {
        val pasta = File(pendrive, "MOSTRAI/update").apply { mkdirs() }
        File(pasta, "Mostrai-Player.apk").writeBytes(apk)
        json?.let { File(pasta, "update.json").writeText(it) }
    }

    object LeitorFalso : LeitorDeApk {
        override fun ler(arquivo: File): InfoApk? {
            val texto = runCatching { arquivo.readText() }.getOrNull() ?: return null
            if (!texto.startsWith("APK|")) return null
            val p = texto.split("|")
            return InfoApk(p[1], p[2].toLong(), p[3], p[4].split(",").filter { it.isNotEmpty() }.toSet())
        }
    }

    /**
     * O `FileProvider` guarda num campo estático o caminho de `filesDir`; o
     * Robolectric troca de pasta a cada teste, então o cache de um teste
     * anterior apontaria para outra pasta. Só existe em teste.
     */
    fun esquecerFileProvider() {
        val campo = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        campo.isAccessible = true
        synchronized(campo.get(null)!!) { (campo.get(null) as MutableMap<*, *>).clear() }
    }
}
