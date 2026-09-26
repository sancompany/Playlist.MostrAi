package br.com.mostrai.player.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.playlist.ItemPlaylist
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Ciclo 2 — o cache sob chamadas simultâneas. */
@RunWith(RobolectricTestRunner::class)
class CacheConcorrenciaTest {

    private lateinit var contexto: Context
    private lateinit var cache: CacheMidia
    private lateinit var servidor: ServidorDeTeste

    private fun hash(bytes: ByteArray) =
        ChaveCache.paraHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun item(caminho: String, criativo: String, contentHash: String? = null) = ItemPlaylist(
        itemProgramacaoId = criativo, criativoId = criativo, duracaoSegundos = 10,
        url = "${servidor.baseUrl}$caminho", contabiliza = true, contentHash = contentHash,
    )

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        File(contexto.cacheDir, "midia").deleteRecursively()
        cache = CacheMidia(contexto)
        servidor = ServidorDeTeste()
    }

    @After
    fun encerrar() = servidor.encerrar()

    @Test
    fun `item ja em cache nao espera o download de outro item`() {
        // O pré-aquecimento baixa a playlist inteira, um item de cada vez. Se
        // a trava for global, um item que JÁ está no cache não consegue
        // começar enquanto outro, completamente diferente, estiver baixando —
        // e um vídeo grande numa internet de loja pode levar minutos.
        servidor.rotas["/a.mp4"] = ServidorDeTeste.Resposta(corpo = "video-a".toByteArray())
        val a = item("/a.mp4", "ca")
        assertNotNull(cache.resolver(a))

        val travaB = CountDownLatch(1)
        servidor.travas["/b.mp4"] = travaB
        servidor.rotas["/b.mp4"] = ServidorDeTeste.Resposta(corpo = "video-b".toByteArray())
        val prewarm = thread { cache.resolver(item("/b.mp4", "cb")) }
        while (servidor.contar("/b.mp4") == 0) Thread.sleep(10)

        val inicio = System.nanoTime()
        val resolvidoA = cache.resolver(a)
        val esperouMs = (System.nanoTime() - inicio) / 1_000_000

        travaB.countDown()
        prewarm.join(5_000)

        assertNotNull(resolvidoA)
        assertTrue("item em cache esperou ${esperouMs}ms por download alheio", esperouMs < 1_000)
    }

    @Test
    fun `falha de hash de um item nao e apagada pelo sucesso de outro`() {
        // BUG-009. O motivo da falha vivia num campo compartilhado. Entre o
        // resolver(A) falhar e o player perguntar "posso tocar da URL?", o
        // pré-aquecimento resolve B com sucesso e zera o campo — e A, a
        // mídia comprovadamente errada, toca da URL remota.
        servidor.rotas["/a.mp4"] = ServidorDeTeste.Resposta(corpo = "adulterado".toByteArray())
        servidor.rotas["/b.mp4"] = ServidorDeTeste.Resposta(corpo = "video-b".toByteArray())
        val a = item("/a.mp4", "ca", contentHash = "f".repeat(64))

        val resolucaoA = cache.resolucao(a)
        cache.resolver(item("/b.mp4", "cb"))

        assertEquals(null, resolucaoA.arquivo)
        assertEquals(false, resolucaoA.podeTocarDaUrlRemota)
    }

    @Test
    fun `midia com hash errado nao e rebaixada a cada vez que o item aparece`() {
        // BUG-006. Um criativo cujo arquivo não bate com o hash continua não
        // batendo até alguém corrigir no backend. Rebaixar a cada vez que o
        // item volta na playlist queima a internet da loja: um vídeo de 50 MB
        // numa playlist de 10 itens de 15s são ~28 GB por dia.
        servidor.rotas["/ruim.mp4"] = ServidorDeTeste.Resposta(corpo = "adulterado".toByteArray())
        val ruim = item("/ruim.mp4", "cr", contentHash = "e".repeat(64))

        repeat(5) { cache.resolucao(ruim) }

        assertEquals(1, servidor.contar("/ruim.mp4"))
    }

    @Test
    fun `o mesmo hash pedido ao mesmo tempo baixa uma vez so`() {
        val corpo = "video-unico".toByteArray()
        val trava = CountDownLatch(1)
        servidor.travas["/u.mp4"] = trava
        servidor.rotas["/u.mp4"] = ServidorDeTeste.Resposta(corpo = corpo)
        val h = hash(corpo)

        val r1 = AtomicReference<File?>()
        val r2 = AtomicReference<File?>()
        val t1 = thread { r1.set(cache.resolver(item("/u.mp4", "c1", h))) }
        while (servidor.contar("/u.mp4") == 0) Thread.sleep(10)
        val t2 = thread { r2.set(cache.resolver(item("/u.mp4", "c2", h))) }
        Thread.sleep(200)
        trava.countDown()
        t1.join(5_000)
        t2.join(5_000)

        assertEquals("o mesmo conteúdo foi baixado mais de uma vez", 1, servidor.contar("/u.mp4"))
        assertEquals(r1.get()?.absolutePath, r2.get()?.absolutePath)
        assertEquals("video-unico", r2.get()?.readText())
    }
}
