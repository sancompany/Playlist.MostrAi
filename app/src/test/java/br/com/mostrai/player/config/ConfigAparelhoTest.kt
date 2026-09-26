package br.com.mostrai.player.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** O que o aparelho guarda (contrato §10) — e só isso. */
@RunWith(RobolectricTestRunner::class)
class ConfigAparelhoTest {

    private lateinit var contexto: Context
    private lateinit var config: ConfigAparelho

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        contexto.getSharedPreferences(ConfigAparelho.ARQUIVO, Context.MODE_PRIVATE).edit().clear().commit()
        config = ConfigAparelho(contexto)
    }

    private fun aplicar(corpo: String) = config.aplicarConfig(ConfigRemotaJson.parse(corpo)!!, corpo)

    @Test
    fun `aparelho novo nao esta provisionado`() {
        assertFalse(config.provisionado)
        assertNull(config.dispositivoId)
        assertNull(config.chaveAparelho)
    }

    @Test
    fun `credencial gravada vale depois de reiniciar o processo`() {
        assertTrue(config.gravarCredenciais("M-0235", "chave"))

        val depois = ConfigAparelho(contexto)
        assertTrue(depois.provisionado)
        assertEquals("M-0235", depois.dispositivoId)
        assertEquals("chave", depois.chaveAparelho)
    }

    @Test
    fun `401 apaga so a chave, o ID fica para preencher a instalacao`() {
        config.gravarCredenciais("M-0235", "chave")

        assertTrue(config.esquecerCredencialSeFor("chave"))

        assertFalse(config.provisionado)
        assertNull(config.chaveAparelho)
        assertEquals("M-0235", config.dispositivoId)
    }

    @Test
    fun `401 atrasado de uma chave antiga nao apaga a chave nova`() {
        config.gravarCredenciais("M-0235", "nova")

        assertFalse(config.esquecerCredencialSeFor("antiga"))
        assertEquals("nova", config.chaveAparelho)
    }

    @Test
    fun `reinstalar em outra tela descarta a config da anterior`() {
        config.gravarCredenciais("M-0001", "chave")
        aplicar("""{"configVersion": 9, "margens": {"superior": 3}}""")

        config.gravarCredenciais("M-0002", "outra")

        assertEquals(0, config.configVersionAplicada)
        assertEquals(MargensOverscan(), config.margens)
    }

    @Test
    fun `reinstalar na mesma tela mantem a config`() {
        config.gravarCredenciais("M-0001", "chave")
        aplicar("""{"configVersion": 9, "margens": {"superior": 3}}""")

        config.gravarCredenciais("M-0001", "nova")

        assertEquals(9, config.configVersionAplicada)
    }

    @Test
    fun `sem config ainda, margem zero, horario aberto e nenhum PIN`() {
        assertEquals(0, config.configVersionAplicada)
        assertEquals(MargensOverscan(), config.margens)
        assertTrue(config.horarioOperacional().estaDentro(java.time.Instant.now()))
        assertNull("nunca existe PIN padrão", config.pinSaida)
    }

    @Test
    fun `config aplicada vale offline, depois de reiniciar`() {
        aplicar("""{"configVersion": 7, "margens": {"superior": 1, "direita": 2, "inferior": 3, "esquerda": 4},
            "pinSaida": "4821"}""")

        val depois = ConfigAparelho(contexto)
        assertEquals(7, depois.configVersionAplicada)
        assertEquals(MargensOverscan(topo = 1f, base = 3f, esquerda = 4f, direita = 2f), depois.margens)
        assertEquals("4821", depois.pinSaida)
    }

    @Test
    fun `PIN trocado no admin substitui o anterior`() {
        aplicar("""{"configVersion": 1, "pinSaida": "4821"}""")
        aplicar("""{"configVersion": 2, "pinSaida": "73915"}""")
        assertEquals("73915", config.pinSaida)
    }

    @Test
    fun `chaves de versoes antigas sao apagadas`() {
        val prefs = contexto.getSharedPreferences(ConfigAparelho.ARQUIVO, Context.MODE_PRIVATE)
        prefs.edit().putString("base_url", "https://antigo").putInt("rotacao_tela", 180)
            .putString("token_provisionamento", "tok").putString("pin_painel", "0000").commit()

        config.limparChavesObsoletas()

        assertFalse(prefs.contains("base_url"))
        assertFalse(prefs.contains("rotacao_tela"))
        assertFalse(prefs.contains("token_provisionamento"))
        assertFalse(prefs.contains("pin_painel"))
    }
}
