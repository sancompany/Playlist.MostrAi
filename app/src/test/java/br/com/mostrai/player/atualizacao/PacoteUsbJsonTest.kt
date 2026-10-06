package br.com.mostrai.player.atualizacao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacoteUsbJsonTest {

    @Test
    fun `le o update json do script de empacotamento`() {
        val j = PacoteUsbJson.parse(
            """{"versionName":"3.0.2","versionCode":7,"sha256":"ABCDEF","certificateSha256":" 8C4E "}""",
        )!!
        assertEquals("3.0.2", j.versionName)
        assertEquals(7L, j.versionCode)
        assertEquals("abcdef", j.sha256)
        assertEquals("8c4e", j.certificateSha256)
    }

    @Test
    fun `campos ausentes ficam nulos`() {
        val j = PacoteUsbJson.parse("{}")!!
        assertNull(j.versionName)
        assertNull(j.versionCode)
        assertNull(j.sha256)
        assertNull(j.certificateSha256)
        assertNull("versionCode negativo não vale", PacoteUsbJson.parse("""{"versionCode":-1}""")!!.versionCode)
    }

    @Test
    fun `texto que nao e JSON e ilegivel`() {
        assertNull(PacoteUsbJson.parse("versionCode=7"))
        assertNull(PacoteUsbJson.parse(""))
        assertNull(PacoteUsbJson.parse("[1,2]"))
    }
}
