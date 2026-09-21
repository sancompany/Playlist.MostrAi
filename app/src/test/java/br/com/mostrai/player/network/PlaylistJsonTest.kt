package br.com.mostrai.player.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistJsonTest {

    @Test
    fun `array puro cai em modo degradado`() {
        val corpo = """
            [
                {"anuncianteId": "anun-1", "url": "https://x/a.mp4", "duracaoSegundos": 15},
                {"anuncianteId": null, "autoanuncio": true, "url": "https://x/auto.mp4", "duracaoSegundos": 10},
                {"anuncianteId": null, "institucional": true, "url": null, "duracaoSegundos": 8}
            ]
        """.trimIndent()

        val playlist = PlaylistJson.parse(corpo)

        assertTrue(playlist.modoDegradado)
        assertNull(playlist.janelaId)
        assertEquals(3, playlist.itens.size)

        val anuncio = playlist.itens[0]
        assertEquals("anun-1", anuncio.anuncianteId)
        assertTrue(anuncio.contabiliza)
        assertNull(anuncio.itemProgramacaoId)
        assertNull(anuncio.criativoId)

        val autoanuncio = playlist.itens[1]
        assertTrue(autoanuncio.autoanuncio)
        assertFalse(autoanuncio.contabiliza)

        val institucional = playlist.itens[2]
        assertTrue(institucional.institucional)
        assertFalse(institucional.contabiliza)
        assertNull(institucional.url)
    }

    @Test
    fun `envelope com versaoContrato usa contrato novo`() {
        val corpo = """
            {
                "versaoContrato": 1,
                "janelaId": "janela-abc",
                "janelaInicio": "2026-09-21T13:00:00-03:00",
                "janelaFim": "2026-09-21T14:00:00-03:00",
                "servidorAgora": "2026-09-21T13:05:00-03:00",
                "itens": [
                    {
                        "itemProgramacaoId": "slot-1",
                        "criativoId": "crv-9",
                        "duracaoSegundos": 20,
                        "url": "https://x/b.mp4",
                        "anuncianteId": "anun-2",
                        "autoanuncio": false,
                        "institucional": false,
                        "contabiliza": true
                    }
                ]
            }
        """.trimIndent()

        val playlist = PlaylistJson.parse(corpo)

        assertFalse(playlist.modoDegradado)
        assertEquals("janela-abc", playlist.janelaId)
        assertEquals("2026-09-21T13:00:00-03:00", playlist.janelaInicio)
        assertEquals(1, playlist.itens.size)

        val item = playlist.itens[0]
        assertEquals("slot-1", item.itemProgramacaoId)
        assertEquals("crv-9", item.criativoId)
        assertTrue(item.contabiliza)
    }

    @Test
    fun `array vazio nao quebra e fica em modo degradado`() {
        val playlist = PlaylistJson.parse("[]")
        assertTrue(playlist.modoDegradado)
        assertTrue(playlist.itens.isEmpty())
    }
}
