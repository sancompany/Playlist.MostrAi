package br.com.mostrai.player.atualizacao

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream

/**
 * Onde um pendrive guarda a atualização. Lugares fixos, nunca busca
 * recursiva (pendrive comum, cheio de fotos, não custa nada):
 *
 * - `MOSTRAI/update/Mostrai-Player.apk` (+ `update.json`) — o formato oficial;
 * - `Mostrai-Player.apk` na raiz — reserva, sem update.json.
 */
interface OrigemPacote {
    /** `null` = este pendrive não tem atualização do Mostraí. */
    fun localizar(): Localizado?

    class Localizado(
        val tamanho: Long,
        val abrirApk: () -> InputStream,
        /** Texto do update.json, ou `null` se não houver. */
        val lerJson: () -> String?,
    )

    companion object {
        const val PASTA = "MOSTRAI"
        const val SUBPASTA = "update"
        const val APK = "Mostrai-Player.apk"
        const val JSON = "update.json"
    }
}

/** Acesso direto ao sistema de arquivos (Android 8/9/10 com permissão de leitura). */
class OrigemArquivo(private val raiz: File) : OrigemPacote {
    override fun localizar(): OrigemPacote.Localizado? {
        val pasta = File(File(raiz, OrigemPacote.PASTA), OrigemPacote.SUBPASTA)
        val oficial = File(pasta, OrigemPacote.APK)
        if (oficial.isFile) {
            val json = File(pasta, OrigemPacote.JSON)
            return OrigemPacote.Localizado(oficial.length(), { oficial.inputStream() }) {
                json.takeIf { it.isFile && it.length() <= PacoteUsbJson.TAMANHO_MAXIMO }?.readText()
            }
        }
        val naRaiz = File(raiz, OrigemPacote.APK)
        if (naRaiz.isFile) return OrigemPacote.Localizado(naRaiz.length(), { naRaiz.inputStream() }) { null }
        return null
    }
}

/**
 * Acesso pelo seletor do Android (Storage Access Framework): o técnico
 * autoriza uma vez a pasta do pendrive e a permissão fica guardada. Usado
 * quando o Android não deixa ler o pendrive direto (Android 11+, ou um
 * firmware que não expõe o USB aos apps). A pasta autorizada pode ser a raiz
 * do pendrive, a `MOSTRAI` ou a `MOSTRAI/update`.
 */
class OrigemDocumento(private val resolver: ContentResolver, private val arvore: Uri) : OrigemPacote {

    private class Doc(val id: String, val nome: String, val pasta: Boolean, val tamanho: Long)

    override fun localizar(): OrigemPacote.Localizado? {
        val raiz = DocumentsContract.getTreeDocumentId(arvore)
        val nomeRaiz = nomeDe(raiz)
        // Desce até MOSTRAI/update a partir do que foi autorizado.
        val pastaUpdate: String? = when {
            nomeRaiz.equals(OrigemPacote.SUBPASTA, ignoreCase = true) -> raiz
            nomeRaiz.equals(OrigemPacote.PASTA, ignoreCase = true) -> filho(raiz, OrigemPacote.SUBPASTA, pasta = true)?.id
            else -> filho(raiz, OrigemPacote.PASTA, pasta = true)?.let { filho(it.id, OrigemPacote.SUBPASTA, pasta = true)?.id }
        }
        pastaUpdate?.let { pasta ->
            filho(pasta, OrigemPacote.APK, pasta = false)?.let { apk ->
                val json = filho(pasta, OrigemPacote.JSON, pasta = false)
                return OrigemPacote.Localizado(apk.tamanho, { abrir(apk.id) }) {
                    json?.takeIf { it.tamanho <= PacoteUsbJson.TAMANHO_MAXIMO }?.let { abrir(it.id).use { s -> s.readBytes().decodeToString() } }
                }
            }
        }
        val naRaiz = filho(raiz, OrigemPacote.APK, pasta = false) ?: return null
        return OrigemPacote.Localizado(naRaiz.tamanho, { abrir(naRaiz.id) }) { null }
    }

    private fun abrir(id: String): InputStream =
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(arvore, id))
            ?: throw java.io.IOException("documento indisponível")

    private fun nomeDe(id: String): String? =
        resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(arvore, id),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun filho(pai: String, nome: String, pasta: Boolean): Doc? {
        val colunas = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(arvore, pai), colunas, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val ehPasta = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                if (ehPasta == pasta && c.getString(1).equals(nome, ignoreCase = true)) {
                    return Doc(c.getString(0), c.getString(1), ehPasta, if (c.isNull(3)) -1L else c.getLong(3))
                }
            }
        }
        return null
    }
}
