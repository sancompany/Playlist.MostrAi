package br.com.mostrai.player.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.playlist.ItemPlaylist
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** R3 e R9: integridade do que vai para a tela. */
@RunWith(RobolectricTestRunner::class)
class CacheMidiaHashTest {

    private lateinit var contexto: Context
    private lateinit var cache: CacheMidia
    private lateinit var servidor: ServidorDeTeste
    private lateinit var base: String

    private var conteudo: ByteArray
        get() = servidor.corpo
        set(valor) { servidor.corpo = valor }

    private fun hashDe(bytes: ByteArray): String =
        ChaveCache.paraHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun item(contentHash: String?, criativoId: String? = "c1") = ItemPlaylist(
        itemProgramacaoId = "i1",
        criativoId = criativoId,
        duracaoSegundos = 10,
        url = "$base/midia.mp4",
        contabiliza = true,
        contentHash = contentHash,
    )

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        File(contexto.cacheDir, "midia").deleteRecursively()
        cache = CacheMidia(contexto)

        servidor = ServidorDeTeste()
        servidor.corpo = "video-bom".toByteArray()
        base = servidor.baseUrl
    }

    @After
    fun encerrar() {
        servidor.encerrar()
    }

    @Test
    fun `hash correto baixa e guarda pelo hash`() {
        val hash = hashDe(conteudo)

        val arquivo = cache.resolver(item(hash))

        assertNotNull(arquivo)
        assertEquals("sha256-$hash", arquivo!!.name)
        assertEquals("video-bom", arquivo.readText())
    }

    @Test
    fun `hash errado nao vira cache`() {
        val arquivo = cache.resolver(item("b".repeat(64)))

        assertNull(arquivo)
        assertEquals(0, cache.arquivos())
    }

    @Test
    fun `hash errado impede tocar a url remota`() {
        // Cair para a URL remota seria servir exatamente o arquivo que acabou
        // de ser rejeitado.
        val resolucao = cache.resolucao(item("b".repeat(64)))

        assertFalse(resolucao.podeTocarDaUrlRemota)
    }

    @Test
    fun `falha de rede continua permitindo tocar da url remota`() {
        val itemQuebrado = item(null).copy(url = "http://127.0.0.1:1/nao-existe.mp4")

        val resolucao = cache.resolucao(itemQuebrado)
        assertNull(resolucao.arquivo)
        assertTrue(resolucao.podeTocarDaUrlRemota)
    }

    @Test
    fun `mesmo criativoId com conteudo novo baixa de novo, nao serve o antigo`() {
        // O defeito original: criativoId como identidade física servia o
        // vídeo velho para sempre quando o backend trocava o arquivo.
        val hashAntigo = hashDe(conteudo)
        val antigo = cache.resolver(item(hashAntigo, criativoId = "mesmo"))!!

        conteudo = "video-novo".toByteArray()
        val hashNovo = hashDe(conteudo)
        val novo = cache.resolver(item(hashNovo, criativoId = "mesmo"))!!

        assertEquals("video-bom", antigo.readText())
        assertEquals("video-novo", novo.readText())
    }

    @Test
    fun `criativos diferentes com o mesmo conteudo reusam o arquivo`() {
        val hash = hashDe(conteudo)

        val um = cache.resolver(item(hash, criativoId = "c1"))!!
        val outro = cache.resolver(item(hash, criativoId = "c2"))!!

        assertEquals(um.absolutePath, outro.absolutePath)
        assertEquals(1, cache.arquivos())
    }

    @Test
    fun `sem hash mantem o comportamento antigo`() {
        val arquivo = cache.resolver(item(null))

        assertNotNull(arquivo)
        assertEquals("criativo-c1", arquivo!!.name)
    }

    @Test
    fun `arquivo ja em cache nao baixa de novo`() {
        val hash = hashDe(conteudo)
        cache.resolver(item(hash))

        conteudo = "outra-coisa".toByteArray()
        val segundo = cache.resolver(item(hash))!!

        assertEquals("video-bom", segundo.readText())
    }

    @Test
    fun `nenhum tmp sobra depois de um hash divergente`() {
        cache.resolver(item("c".repeat(64)))

        val tmp = File(contexto.cacheDir, "midia").listFiles()?.filter { it.name.endsWith(".tmp") }
        assertEquals(0, tmp?.size)
    }

    @Test
    fun `resposta vazia e recusada mesmo sem hash`() {
        conteudo = ByteArray(0)

        assertNull(cache.resolver(item(null)))
    }

    @Test
    fun `pagina html de portal cativo nao vira cache de midia`() {
        // Wi-Fi de loja com portal cativo, ou proxy, responde 200 com uma
        // página HTML para qualquer URL. Sem contentHash (V1) nada mais
        // confere o conteúdo: a página ficava gravada com o nome do criativo
        // e o caminho rápido a servia para sempre — o criativo falhava em
        // toda exibição e nunca era baixado de novo.
        servidor.rotas["/midia.mp4"] = ServidorDeTeste.Resposta(
            corpo = "<html>faça login no Wi-Fi</html>".toByteArray(),
            tipo = "text/html; charset=utf-8",
        )

        assertNull(cache.resolver(item(null)))
        assertEquals(0, cache.arquivos())
    }

    @Test
    fun `video servido como text plain continua indo para o cache`() {
        // Auditoria B (BUG-029): Supabase Storage e outros gravam
        // "text/plain;charset=UTF-8" quando o upload não informa o tipo.
        // Recusar todo text/* desligava o cache da frota inteira — cada
        // exibição rebaixando o vídeo.
        servidor.rotas["/midia.mp4"] = ServidorDeTeste.Resposta(
            corpo = "video-bom".toByteArray(),
            tipo = "text/plain;charset=UTF-8",
        )

        assertNotNull(cache.resolver(item(null)))
        assertEquals(1, cache.arquivos())
    }

    @Test
    fun `url com esquema invalido nao derruba o app`() {
        val itemRuim = item(null).copy(url = "ftp://exemplo.com/v.mp4")

        assertNull(cache.resolver(itemRuim))
    }
}
