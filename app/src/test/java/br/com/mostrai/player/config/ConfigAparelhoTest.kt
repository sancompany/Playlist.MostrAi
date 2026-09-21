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
 * O PIN do painel de manutenção só pode ser o que o teclado de
 * [br.com.mostrai.player.ui.PainelActivity] consegue digitar de volta: 4
 * dígitos, nada mais. Um PIN fora disso, vindo de qualquer provisionamento
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
    fun `pin de 4 digitos e aceito`() {
        config.pinPainel = "1357"
        assertEquals("1357", config.pinPainel)
    }

    @Test
    fun `pin com menos de 4 digitos e ignorado`() {
        config.pinPainel = "123"
        assertEquals(ConfigAparelho.PIN_PROVISORIO, config.pinPainel)
    }

    @Test
    fun `pin com mais de 4 digitos e ignorado`() {
        config.pinPainel = "12345"
        assertEquals(ConfigAparelho.PIN_PROVISORIO, config.pinPainel)
    }

    @Test
    fun `pin com letra ou simbolo e ignorado`() {
        config.pinPainel = "12a4"
        assertEquals(ConfigAparelho.PIN_PROVISORIO, config.pinPainel)

        config.pinPainel = "12-4"
        assertEquals(ConfigAparelho.PIN_PROVISORIO, config.pinPainel)
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
        assertTrue(ConfigAparelho.ehPinValido("9999"))
        assertFalse(ConfigAparelho.ehPinValido(""))
        assertFalse(ConfigAparelho.ehPinValido("123"))
        assertFalse(ConfigAparelho.ehPinValido("12345"))
        assertFalse(ConfigAparelho.ehPinValido("12a4"))
    }
}
