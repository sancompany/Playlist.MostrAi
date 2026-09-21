package br.com.mostrai.player.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `rotacaoTela` guarda a compensação de um painel montado fisicamente de
 * lado (sinalização digital em espaço estreito) — testa que só o conjunto
 * fechado {0, 90, 180, 270} é aceito, nunca gira a esmo com um valor
 * inesperado.
 *
 * `pinPainel` só pode ser o que o teclado de
 * [br.com.mostrai.player.ui.PainelActivity] consegue digitar de volta: 4 a
 * 6 dígitos numéricos. Um PIN fora disso, vindo de qualquer provisionamento
 * (build embutido, `adb`, `mostrai-config.json`), trancaria o painel para
 * sempre — por isso o valor inválido é ignorado, não gravado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ConfigAparelhoTest {

    private lateinit var config: ConfigAparelho

    @Before
    fun setUp() {
        config = ConfigAparelho(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `sem configurar, rotacao e zero`() {
        assertEquals(0, config.rotacaoTela)
    }

    @Test
    fun `aceita os quatro valores validos`() {
        for (valor in listOf(0, 90, 180, 270)) {
            config.rotacaoTela = valor
            assertEquals(valor, config.rotacaoTela)
        }
    }

    @Test
    fun `valor fora do conjunto vira zero, nunca gira a esmo`() {
        config.rotacaoTela = 45
        assertEquals(0, config.rotacaoTela)

        config.rotacaoTela = -90
        assertEquals(0, config.rotacaoTela)

        config.rotacaoTela = 360
        assertEquals(0, config.rotacaoTela)
    }

    @Test
    fun `sem configurar, pin e o provisorio de fabrica`() {
        assertEquals("0000", config.pinPainel)
    }

    @Test
    fun `aceita pin numerico de 4 a 6 digitos`() {
        for (pin in listOf("1234", "13579", "024680")) {
            config.pinPainel = pin
            assertEquals(pin, config.pinPainel)
        }
    }

    @Test
    fun `pin fora de 4 a 6 digitos e ignorado, mantem o anterior`() {
        config.pinPainel = "123"
        assertEquals("0000", config.pinPainel)

        config.pinPainel = "1234567"
        assertEquals("0000", config.pinPainel)
    }

    @Test
    fun `pin nao numerico e ignorado, nunca tranca o painel`() {
        config.pinPainel = "12ab"
        assertEquals("0000", config.pinPainel)

        config.pinPainel = "12-4"
        assertEquals("0000", config.pinPainel)
    }

    @Test
    fun `pin invalido nao sobrescreve um pin valido ja gravado`() {
        config.pinPainel = "9876"
        config.pinPainel = "abcde"
        assertEquals("9876", config.pinPainel)
    }

    @Test
    fun `ehPinValido cobre os casos`() {
        assertTrue(ConfigAparelho.ehPinValido("0000"))
        assertTrue(ConfigAparelho.ehPinValido("13579"))
        assertTrue(ConfigAparelho.ehPinValido("024680"))
        assertFalse(ConfigAparelho.ehPinValido(""))
        assertFalse(ConfigAparelho.ehPinValido("123"))
        assertFalse(ConfigAparelho.ehPinValido("1234567"))
        assertFalse(ConfigAparelho.ehPinValido("12a4"))
    }
}
