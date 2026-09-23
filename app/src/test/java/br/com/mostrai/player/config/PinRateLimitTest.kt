package br.com.mostrai.player.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Parte 11: o PIN administrativo não pode ser forçado por bruta. */
@RunWith(RobolectricTestRunner::class)
class PinRateLimitTest {

    private lateinit var config: ConfigAparelho

    @Before
    fun preparar() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        contexto.getSharedPreferences("mostrai_config", Context.MODE_PRIVATE).edit().clear().commit()
        config = ConfigAparelho(contexto)
    }

    @Test
    fun `sem erro nenhum nao ha bloqueio`() {
        assertEquals(0L, config.pinBloqueadoPorMs())
    }

    @Test
    fun `os primeiros erros ainda nao bloqueiam`() {
        val agora = 1_000_000L
        repeat(ConfigAparelho.ERROS_PIN_ANTES_DE_BLOQUEAR - 1) { config.registrarPinErrado(agora) }

        assertEquals(0L, config.pinBloqueadoPorMs(agora))
    }

    @Test
    fun `a partir do limite comeca a bloquear`() {
        val agora = 1_000_000L
        repeat(ConfigAparelho.ERROS_PIN_ANTES_DE_BLOQUEAR) { config.registrarPinErrado(agora) }

        assertEquals(ConfigAparelho.BLOQUEIO_PIN_BASE_MS, config.pinBloqueadoPorMs(agora))
    }

    @Test
    fun `cada erro a mais dobra a espera`() {
        val agora = 1_000_000L
        repeat(ConfigAparelho.ERROS_PIN_ANTES_DE_BLOQUEAR) { config.registrarPinErrado(agora) }
        val primeiro = config.pinBloqueadoPorMs(agora)

        config.registrarPinErrado(agora)
        val segundo = config.pinBloqueadoPorMs(agora)

        assertEquals(primeiro * 2, segundo)
    }

    @Test
    fun `bloqueio tem teto, nunca tranca a manutencao para sempre`() {
        val agora = 1_000_000L
        repeat(50) { config.registrarPinErrado(agora) }

        assertEquals(ConfigAparelho.BLOQUEIO_PIN_MAXIMO_MS, config.pinBloqueadoPorMs(agora))
    }

    @Test
    fun `o bloqueio expira com o tempo`() {
        val agora = 1_000_000L
        repeat(ConfigAparelho.ERROS_PIN_ANTES_DE_BLOQUEAR) { config.registrarPinErrado(agora) }

        val depois = agora + ConfigAparelho.BLOQUEIO_PIN_BASE_MS + 1
        assertEquals(0L, config.pinBloqueadoPorMs(depois))
    }

    @Test
    fun `acertar o PIN zera tudo`() {
        val agora = 1_000_000L
        repeat(10) { config.registrarPinErrado(agora) }
        assertTrue(config.pinBloqueadoPorMs(agora) > 0)

        config.registrarPinCerto()

        assertEquals(0L, config.pinBloqueadoPorMs(agora))
    }

    @Test
    fun `o ultimo PIN valido sobrevive para operacao offline`() {
        config.pinPainel = "4821"
        // Uma config remota que chega com PIN inválido não pode apagar o que
        // funciona — senão uma tela sem internet fica sem manutenção.
        config.pinPainel = "abc"

        assertEquals("4821", config.pinPainel)
    }
}
