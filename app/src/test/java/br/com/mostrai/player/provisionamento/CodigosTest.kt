package br.com.mostrai.player.provisionamento

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mesmas regras de `src/lib/codigo-tela.js` do backend (contrato §2 e §3). */
class CodigosTest {

    @Test
    fun `todas as grafias do ID normalizam para M-0235`() {
        listOf("235", "0235", "M0235", "m0235", "M-0235", "m-0235", " M-0235 ", "M 0235", "00235").forEach {
            assertEquals("entrada '$it'", "M-0235", CodigoTela.normalizar(it))
        }
    }

    @Test
    fun `ID acima de 4 digitos nunca e truncado`() {
        assertEquals("M-12345", CodigoTela.normalizar("12345"))
        assertEquals("M-12345", CodigoTela.normalizar("m-12345"))
        assertEquals("M-0001", CodigoTela.normalizar("1"))
        assertEquals("M-9999", CodigoTela.normalizar("9999"))
    }

    @Test
    fun `ID invalido e recusado antes de ir ao servidor`() {
        listOf("", "M-", "0", "0000", "X-0235", "M-02a5", "12345678901", "2147483648").forEach {
            assertNull("entrada '$it'", CodigoTela.normalizar(it))
        }
        assertEquals("M-2147483647", CodigoTela.normalizar("2147483647"))
    }

    @Test
    fun `codigo com ou sem hifen, em qualquer caixa, vira XXXX-XXXX`() {
        listOf("7K4M-9Q2W", "7K4M9Q2W", "7k4m9q2w", "7k4m-9q2w", " 7K4M 9Q2W ").forEach {
            assertEquals("entrada '$it'", "7K4M-9Q2W", CodigoInstalacao.normalizar(it))
        }
    }

    @Test
    fun `codigo fora do alfabeto ou do tamanho e recusado`() {
        // 0, O, 1, I e L não existem no alfabeto (ambíguos na TV).
        listOf("7K4M-9Q2", "7K4M-9Q2WW", "0K4M-9Q2W", "OK4M-9Q2W", "1K4M-9Q2W", "IK4M-9Q2W", "LK4M-9Q2W", "").forEach {
            assertNull("entrada '$it'", CodigoInstalacao.normalizar(it))
        }
    }

    @Test
    fun `formatacao parcial acompanha a digitacao`() {
        assertEquals("7K4", CodigoInstalacao.formatarParcial("7K4"))
        assertEquals("7K4M", CodigoInstalacao.formatarParcial("7K4M"))
        assertEquals("7K4M-9", CodigoInstalacao.formatarParcial("7K4M9"))
    }
}
