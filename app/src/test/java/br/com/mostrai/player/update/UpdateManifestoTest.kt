package br.com.mostrai.player.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateManifestoTest {

    private val hash = "a".repeat(64)

    private fun json(texto: String) = JSONObject(texto)

    @Test
    fun `le o manifesto completo`() {
        val manifesto = UpdateManifesto.parse(
            json(
                """
                {"available": true, "required": true, "version": "1.2.0", "build": 42,
                 "url": "https://exemplo.com/app.apk", "sha256": "$hash", "size": 12345678}
                """.trimIndent()
            )
        )!!

        assertEquals("1.2.0", manifesto.versao)
        assertEquals(42, manifesto.build)
        assertEquals("https://exemplo.com/app.apk", manifesto.url)
        assertEquals(hash, manifesto.sha256)
        assertEquals(12345678L, manifesto.tamanhoBytes)
        assertEquals(true, manifesto.obrigatorio)
    }

    @Test
    fun `available false nao e update`() {
        assertNull(UpdateManifesto.parse(json("""{"available": false}""")))
    }

    @Test
    fun `sem hash e recusado`() {
        // Instalar binário não verificado numa frota é o tipo de erro que não
        // se conserta remotamente depois.
        assertNull(
            UpdateManifesto.parse(
                json("""{"available": true, "build": 42, "url": "https://exemplo.com/a.apk"}""")
            )
        )
    }

    @Test
    fun `hash malformado e recusado`() {
        assertNull(
            UpdateManifesto.parse(
                json(
                    """{"available": true, "build": 42, "url": "https://x/a.apk", "sha256": "abc"}"""
                )
            )
        )
    }

    @Test
    fun `sem url e recusado`() {
        assertNull(
            UpdateManifesto.parse(json("""{"available": true, "build": 42, "sha256": "$hash"}"""))
        )
    }

    @Test
    fun `sem build e recusado`() {
        assertNull(
            UpdateManifesto.parse(
                json("""{"available": true, "url": "https://x/a.apk", "sha256": "$hash"}""")
            )
        )
    }

    @Test
    fun `required ausente significa recomendado, nao obrigatorio`() {
        val manifesto = UpdateManifesto.parse(
            json(
                """{"available": true, "build": 42, "url": "https://x/a.apk", "sha256": "$hash"}"""
            )
        )!!

        assertFalse(manifesto.obrigatorio)
    }

    @Test
    fun `aceita as chaves em portugues tambem`() {
        val manifesto = UpdateManifesto.parse(
            json(
                """{"disponivel": true, "obrigatorio": true, "versao": "2.0", "build": 7,
                    "url": "https://x/a.apk", "sha256": "$hash"}"""
            )
        )

        assertNotNull(manifesto)
        assertEquals("2.0", manifesto!!.versao)
        assertEquals(true, manifesto.obrigatorio)
    }

    @Test
    fun `hash maiusculo e normalizado`() {
        val manifesto = UpdateManifesto.parse(
            json(
                """{"available": true, "build": 9, "url": "https://x/a.apk",
                    "sha256": "${hash.uppercase()}"}"""
            )
        )!!

        assertEquals(hash, manifesto.sha256)
    }
}
