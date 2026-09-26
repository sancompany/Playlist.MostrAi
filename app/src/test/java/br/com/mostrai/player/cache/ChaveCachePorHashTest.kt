package br.com.mostrai.player.cache

import br.com.mostrai.player.playlist.ItemPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cobre o endereçamento por conteúdo (R3), separado do teste original. */
class ChaveCachePorHashTest {

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    private fun item(
        criativoId: String? = "criativo-1",
        url: String? = "https://exemplo.com/v.mp4",
        contentHash: String? = null,
    ) = ItemPlaylist(
        itemProgramacaoId = "i1",
        criativoId = criativoId,
        duracaoSegundos = 10,
        url = url,
        contabiliza = true,
        contentHash = contentHash,
    )

    @Test
    fun `hash tem precedencia sobre criativoId`() {
        assertEquals("sha256-$hashA", ChaveCache.paraItem(item(contentHash = hashA)))
    }

    @Test
    fun `mesmo criativoId com hash novo vira chave nova`() {
        // O defeito que motivou tudo isto: com criativoId como identidade
        // física, trocar o arquivo mantendo o id servia o vídeo antigo para
        // sempre. Com hash, o arquivo novo simplesmente tem outro nome.
        val antes = ChaveCache.paraItem(item(criativoId = "mesmo", contentHash = hashA))
        val depois = ChaveCache.paraItem(item(criativoId = "mesmo", contentHash = hashB))

        assertNotEquals(antes, depois)
    }

    @Test
    fun `criativos diferentes com o mesmo conteudo compartilham o arquivo`() {
        val um = ChaveCache.paraItem(item(criativoId = "c1", contentHash = hashA))
        val outro = ChaveCache.paraItem(item(criativoId = "c2", contentHash = hashA))

        assertEquals(um, outro)
    }

    @Test
    fun `sem hash mantem o comportamento antigo`() {
        assertEquals("criativo-criativo-1", ChaveCache.paraItem(item()))
    }

    @Test
    fun `hash malformado cai no fallback em vez de virar chave de lixo`() {
        assertEquals("criativo-criativo-1", ChaveCache.paraItem(item(contentHash = "abc")))
        assertEquals("criativo-criativo-1", ChaveCache.paraItem(item(contentHash = "z".repeat(64))))
    }

    @Test
    fun `hash maiusculo e normalizado`() {
        assertEquals("sha256-$hashA", ChaveCache.paraItem(item(contentHash = hashA.uppercase())))
    }

    @Test
    fun `ehHexSha256 aceita so 64 hexadecimais`() {
        assertTrue(ChaveCache.ehHexSha256(hashA))
        assertTrue(ChaveCache.ehHexSha256(hashA.uppercase()))
        assertFalse(ChaveCache.ehHexSha256("a".repeat(63)))
        assertFalse(ChaveCache.ehHexSha256("a".repeat(65)))
        assertFalse(ChaveCache.ehHexSha256("g".repeat(64)))
    }

    @Test
    fun `sem criativoId e sem hash usa a url`() {
        val chave = ChaveCache.paraItem(item(criativoId = null))
        assertTrue(chave!!.startsWith("url-"))
    }

    @Test
    fun `sem url e sem identidade nenhuma nao ha chave`() {
        org.junit.Assert.assertNull(ChaveCache.paraItem(item(criativoId = null, url = null)))
    }

    @Test
    fun `paraHex produz hexadecimal minusculo de dois digitos por byte`() {
        assertEquals("00ff0a", ChaveCache.paraHex(byteArrayOf(0, -1, 10)))
    }
}
