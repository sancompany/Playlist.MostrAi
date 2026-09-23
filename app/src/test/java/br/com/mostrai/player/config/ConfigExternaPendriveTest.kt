package br.com.mostrai.player.config

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Auditoria E — o pendrive como caminho de recuperação em campo. */
@RunWith(RobolectricTestRunner::class)
class ConfigExternaPendriveTest {

    private lateinit var contexto: Context
    private lateinit var arquivo: File

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        contexto.getSharedPreferences("mostrai_config", Context.MODE_PRIVATE).edit().clear().commit()
        arquivo = File(Environment.getExternalStorageDirectory(), "mostrai-config.json")
        arquivo.parentFile?.mkdirs()
    }

    @After
    fun limpar() {
        arquivo.delete()
    }

    @Test
    fun `pendrive com token novo substitui um token que nunca virou credencial`() {
        // O primeiro token expirou (ou foi queimado sem a resposta chegar).
        // O técnico leva um pendrive com um token novo; antes, qualquer token
        // já gravado fazia o app ignorar o pendrive para sempre — só limpando
        // os dados do app a TV voltava.
        val config = ConfigAparelho(contexto).apply {
            baseUrl = "https://exemplo.com"
            tokenProvisionamento = "tok_velho"
        }
        arquivo.writeText("""{"baseUrl": "https://exemplo.com", "tokenProvisionamento": "tok_novo"}""")

        ConfigExterna.procurarEAplicar(contexto, config)

        assertEquals("tok_novo", config.tokenProvisionamento)
    }

    @Test
    fun `pendrive com credencial completa provisiona mesmo com token antigo gravado`() {
        val config = ConfigAparelho(contexto).apply {
            baseUrl = "https://exemplo.com"
            tokenProvisionamento = "tok_velho"
        }
        arquivo.writeText(
            """{"baseUrl": "https://exemplo.com", "dispositivoId": "tela-9", "chaveAparelho": "k9"}""",
        )

        ConfigExterna.procurarEAplicar(contexto, config)

        assertTrue(config.provisionado)
    }

    @Test
    fun `mesmo token do pendrive nao e reaplicado`() {
        val config = ConfigAparelho(contexto).apply {
            baseUrl = "https://exemplo.com"
            tokenProvisionamento = "tok_1"
        }
        arquivo.writeText("""{"baseUrl": "https://outro.com", "tokenProvisionamento": "tok_1"}""")

        ConfigExterna.procurarEAplicar(contexto, config)

        assertEquals("https://exemplo.com", config.baseUrl)
    }
}
