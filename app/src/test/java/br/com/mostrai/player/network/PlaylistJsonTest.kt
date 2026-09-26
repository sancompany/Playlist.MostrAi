package br.com.mostrai.player.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Só o envelope do contrato §7 é playlist. Nada de array V1. */
class PlaylistJsonTest {

    private val exemplo = """
        {
          "versaoContrato": 2,
          "janelaId": "235|2026-09-26T14:00:00.000Z",
          "janelaInicio": "2026-09-26T14:00:00.000Z",
          "janelaFim": "2026-09-26T15:00:00.000Z",
          "servidorAgora": "2026-09-26T14:07:31.512Z",
          "itens": [
            {
              "itemProgramacaoId": "235|2026-09-26T14:00:00.000Z|0|17",
              "criativoId": "88", "anuncianteId": 17, "autoanuncio": false, "institucional": false,
              "contabiliza": true, "url": "https://x/criativos/88.mp4", "duracaoSegundos": 15,
              "contentHash": "${"3F".repeat(32)}"
            },
            {
              "itemProgramacaoId": "235|2026-09-26T14:00:00.000Z|1|inst",
              "criativoId": null, "institucional": true, "contabiliza": false,
              "url": null, "duracaoSegundos": 20
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `le o envelope do contrato`() {
        val playlist = PlaylistJson.parse(exemplo)!!

        assertEquals("235|2026-09-26T14:00:00.000Z", playlist.janelaId)
        assertEquals("2026-09-26T14:00:00.000Z", playlist.janelaInicio)
        assertEquals("2026-09-26T14:07:31.512Z", playlist.servidorAgora)
        assertEquals(2, playlist.itens.size)

        val anuncio = playlist.itens[0]
        assertEquals("235|2026-09-26T14:00:00.000Z|0|17", anuncio.itemProgramacaoId)
        assertEquals("88", anuncio.criativoId)
        assertEquals(15, anuncio.duracaoSegundos)
        assertTrue(anuncio.contabiliza)
        assertEquals("3f".repeat(32), anuncio.contentHash)
    }

    @Test
    fun `institucional sem url vira item de cartao local que nao conta`() {
        val institucional = PlaylistJson.parse(exemplo)!!.itens[1]

        assertNull(institucional.url)
        assertNull(institucional.criativoId)
        assertFalse(institucional.contabiliza)
        assertEquals(20, institucional.duracaoSegundos)
    }

    @Test
    fun `array solto do contrato antigo nao e playlist`() {
        assertNull(PlaylistJson.parse("""[{"url":"https://x/v.mp4","duracaoSegundos":10}]"""))
    }

    @Test
    fun `envelope sem itens ou sem janelaId nao e playlist`() {
        assertNull(PlaylistJson.parse("""{"janelaId":"j"}"""))
        assertNull(PlaylistJson.parse("""{"itens":[]}"""))
        assertNull(PlaylistJson.parse("<html>502</html>"))
    }

    @Test
    fun `lista vazia e playlist valida sem itens`() {
        assertEquals(0, PlaylistJson.parse("""{"janelaId":"j","itens":[]}""")!!.itens.size)
    }
}
