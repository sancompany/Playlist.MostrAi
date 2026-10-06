package br.com.mostrai.player.atualizacao

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Android 11+ (ou firmware que esconde o USB): o pendrive é lido pela pasta
 * que o técnico autorizou no seletor do Android. Aqui um provedor de
 * documentos falso serve uma pasta temporária como se fosse o pendrive.
 */
@RunWith(RobolectricTestRunner::class)
class OrigemDocumentoTest {

    @get:Rule val tmp = TemporaryFolder()

    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private lateinit var pendrive: File

    @Before
    fun preparar() {
        ProvedorFalso.base = tmp.root
        pendrive = tmp.newFolder("PENDRIVE")
        Robolectric.setupContentProvider(ProvedorFalso::class.java, ProvedorFalso.AUTORIDADE)
    }

    private fun arvore(docId: String) = DocumentsContract.buildTreeDocumentUri(ProvedorFalso.AUTORIDADE, docId)

    private fun localizar(docId: String = "PENDRIVE") = OrigemDocumento(contexto.contentResolver, arvore(docId)).localizar()

    @Test
    fun `pasta autorizada na raiz do pendrive acha o pacote oficial`() {
        val apk = ApkFalso.bytes()
        ApkFalso.gravarOficial(pendrive, apk)
        val achado = localizar()!!
        assertEquals(apk.size.toLong(), achado.tamanho)
        assertArrayEquals(apk, achado.abrirApk().use { it.readBytes() })
        assertEquals(ApkFalso.json(apk), achado.lerJson())
    }

    @Test
    fun `pasta autorizada pode ser MOSTRAI ou MOSTRAI-update`() {
        ApkFalso.gravarOficial(pendrive)
        assertNotNull(localizar("PENDRIVE/MOSTRAI"))
        assertNotNull(localizar("PENDRIVE/MOSTRAI/update"))
    }

    @Test
    fun `nomes sem diferenca de maiusculas, como o FAT do pendrive`() {
        File(pendrive, "mostrai/UPDATE").mkdirs()
        File(pendrive, "mostrai/UPDATE/mostrai-player.APK").writeBytes(ApkFalso.bytes())
        assertNotNull(localizar())
    }

    @Test
    fun `APK na raiz vale sem update json`() {
        File(pendrive, "Mostrai-Player.apk").writeBytes(ApkFalso.bytes())
        val achado = localizar()!!
        assertNull(achado.lerJson())
    }

    @Test
    fun `pendrive comum nao tem nada`() {
        File(pendrive, "fotos").mkdirs()
        File(pendrive, "fotos/Mostrai-Player.apk").writeBytes(ApkFalso.bytes())
        assertNull(localizar())
    }

    @Test
    fun `a verificacao completa funciona pelo seletor`() {
        ApkFalso.gravarOficial(pendrive)
        val verificador = VerificadorUsb(
            File(tmp.root, "privado"), ApkFalso.LeitorFalso, { ApkFalso.instalado }, ApkFalso.OFICIAL,
            { 50L shl 30 }, { 1L shl 30 },
        )
        val r = verificador.verificar(OrigemDocumento(contexto.contentResolver, arvore("PENDRIVE")))
        assertEquals(7L, (r as VerificadorUsb.Resultado.Candidato).apk.versionCode)
    }

    /** Documento = caminho relativo a [base]. */
    class ProvedorFalso : ContentProvider() {
        companion object {
            const val AUTORIDADE = "br.com.mostrai.teste.documentos"
            lateinit var base: File
        }

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, ordem: String?): Cursor {
            val colunas = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
            val segmentos = uri.pathSegments
            val docId = segmentos[3]
            val cursor = MatrixCursor(colunas)
            val alvo = File(base, docId)
            val linhas = if (segmentos.getOrNull(4) == "children") alvo.listFiles().orEmpty().sortedBy { it.name } else listOf(alvo)
            linhas.forEach { f ->
                cursor.addRow(
                    colunas.map<String, Any?> { c ->
                        when (c) {
                            Document.COLUMN_DOCUMENT_ID -> f.relativeTo(base).path
                            Document.COLUMN_DISPLAY_NAME -> f.name
                            Document.COLUMN_MIME_TYPE -> if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
                            Document.COLUMN_SIZE -> if (f.isDirectory) null else f.length()
                            else -> null
                        }
                    }.toTypedArray(),
                )
            }
            return cursor
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(File(base, uri.pathSegments[3]), ParcelFileDescriptor.MODE_READ_ONLY)

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }
}
