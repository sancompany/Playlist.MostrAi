package br.com.mostrai.player.cache

import br.com.mostrai.player.playlist.ItemPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChaveCacheTest {

    private fun item(criativoId: String?, url: String?) = ItemPlaylist(
        itemProgramacaoId = "slot-1",
        criativoId = criativoId,
        duracaoSegundos = 10,
        url = url,
        contabiliza = true,
    )

    @Test
    fun `com criativoId usa ele como chave, nunca a url`() {
        val a = item(criativoId = "abc123", url = "https://exemplo/v1.mp4")
        val b = item(criativoId = "abc123", url = "https://exemplo/v2-diferente.mp4")
        // criativoId -> url é imutável por contrato: mesma chave mesmo se a
        // url mudasse (o que não deveria acontecer, mas a chave não depende dela).
        assertEquals(ChaveCache.paraItem(a), ChaveCache.paraItem(b))
    }

    @Test
    fun `criativoId vira parte visivel da chave`() {
        val chave = ChaveCache.paraItem(item(criativoId = "abc-123", url = "https://exemplo/v.mp4"))
        assertEquals("criativo-abc-123", chave)
    }

    @Test
    fun `caracteres fora do seguro sao sanitizados`() {
        val chave = ChaveCache.paraItem(item(criativoId = "abc/123 xyz", url = "https://exemplo/v.mp4"))
        assertEquals("criativo-abc_123_xyz", chave)
    }

    @Test
    fun `sem criativoId cai para hash da url (modo degradado)`() {
        val chave = ChaveCache.paraItem(item(criativoId = null, url = "https://exemplo/video.mp4"))
        assertEquals(true, chave!!.startsWith("url-"))
    }

    @Test
    fun `urls diferentes em modo degradado geram chaves diferentes`() {
        val c1 = ChaveCache.paraItem(item(criativoId = null, url = "https://exemplo/a.mp4"))
        val c2 = ChaveCache.paraItem(item(criativoId = null, url = "https://exemplo/b.mp4"))
        assertNotEquals(c1, c2)
    }

    @Test
    fun `sem criativoId e sem url nao ha como cachear`() {
        assertNull(ChaveCache.paraItem(item(criativoId = null, url = null)))
    }
}
