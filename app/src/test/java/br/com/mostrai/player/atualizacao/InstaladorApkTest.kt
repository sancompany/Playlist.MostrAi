package br.com.mostrai.player.atualizacao

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** A cópia validada chega ao instalador do Android por content://, nunca file://. */
@RunWith(RobolectricTestRunner::class)
class InstaladorApkTest {

    private val contexto: Context = ApplicationProvider.getApplicationContext()

    @org.junit.Before
    fun preparar() = ApkFalso.esquecerFileProvider()

    private fun copiaAprovada(): File =
        File(contexto.filesDir, "atualizacao/Mostrai-Player.apk").apply {
            parentFile!!.mkdirs()
            writeBytes(ApkFalso.bytes())
        }

    @Test
    fun `FileProvider entrega content uri da autoridade do Player`() {
        val uri = InstaladorApk.uri(contexto, copiaAprovada())
        assertEquals("content", uri.scheme)
        assertEquals("br.com.mostrai.player.atualizacao", uri.authority)
        assertFalse("nada de caminho real na URI", uri.toString().contains(contexto.filesDir.absolutePath))
        contexto.contentResolver.openInputStream(uri)!!.use {
            assertEquals(String(ApkFalso.bytes()), String(it.readBytes()))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `FileProvider nao expoe nada fora da pasta da atualizacao`() {
        val credencial = File(contexto.filesDir, "outra/segredo.txt").apply { parentFile!!.mkdirs(); writeText("x") }
        InstaladorApk.uri(contexto, credencial)
    }

    @Test
    fun `Intent do instalador e VIEW do APK com leitura concedida so a quem recebe`() {
        val intent = InstaladorApk.intentInstalar(contexto, copiaAprovada())
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data!!.scheme)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse("sem escrita", intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue("nada de extra que pule a confirmação", intent.extras?.isEmpty ?: true)
    }

    @Test
    fun `permissao de instalar segue o que o Android diz deste app`() {
        val pm = shadowOf(contexto.packageManager)
        pm.setCanRequestPackageInstalls(false)
        assertFalse(InstaladorApk.podeInstalar(contexto))
        pm.setCanRequestPackageInstalls(true)
        assertTrue(InstaladorApk.podeInstalar(contexto))
    }

    @Test
    fun `liberar instalacoes abre primeiro a tela do proprio Mostrai`() {
        val intents = InstaladorApk.intentsPermissao(contexto)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intents.first().action)
        assertEquals("package:br.com.mostrai.player", intents.first().dataString)
        assertEquals(Settings.ACTION_SETTINGS, intents.last().action)
    }
}
