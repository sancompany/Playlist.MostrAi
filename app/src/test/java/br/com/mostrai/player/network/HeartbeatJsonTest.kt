package br.com.mostrai.player.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartbeatJsonTest {

    @Test
    fun `parseMargens le os 4 lados, superior e inferior viram topo e base`() {
        val corpo = """{"ok":true,"margens":{"superior":3,"direita":1.5,"inferior":0,"esquerda":2}}"""

        val margens = HeartbeatJson.parseMargens(corpo)

        assertEquals(3f, margens?.topo)
        assertEquals(0f, margens?.base)
        assertEquals(2f, margens?.esquerda)
        assertEquals(1.5f, margens?.direita)
    }

    @Test
    fun `parseMargens sem o campo margens devolve null`() {
        assertNull(HeartbeatJson.parseMargens("""{"ok":true}"""))
    }

    @Test
    fun `parseMargens com corpo malformado devolve null, nunca lanca`() {
        assertNull(HeartbeatJson.parseMargens("não é json"))
        assertNull(HeartbeatJson.parseMargens(""))
    }

    @Test
    fun `parseMargens com lado ausente no objeto vira zero`() {
        val margens = HeartbeatJson.parseMargens("""{"margens":{"superior":5}}""")

        assertEquals(5f, margens?.topo)
        assertEquals(0f, margens?.base)
        assertEquals(0f, margens?.esquerda)
        assertEquals(0f, margens?.direita)
    }
}
