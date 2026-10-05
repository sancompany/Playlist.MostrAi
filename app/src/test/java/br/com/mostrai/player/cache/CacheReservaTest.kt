package br.com.mostrai.player.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.playlist.ItemPlaylist
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * V1 de produção (05/10/2026): a reserva de disco (sistema + fila de
 * comprovantes) vale durante o download, não só antes de começar.
 */
@RunWith(RobolectricTestRunner::class)
class CacheReservaTest {

    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private lateinit var servidor: ServidorDeTeste
    private val mb = 1024L * 1024

    @Before
    fun preparar() {
        File(contexto.filesDir, "midia").deleteRecursively()
        servidor = ServidorDeTeste()
    }

    @After
    fun encerrar() = servidor.encerrar()

    private fun item(nome: String) = ItemPlaylist("i-$nome", nome, 15, "${servidor.baseUrl}/$nome.mp4", contabiliza = true)

    private fun parciais() = File(contexto.filesDir, "midia").listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()

    @Test
    fun `video anunciado maior que a folga nem comeca, e o disco fica como estava`() {
        servidor.corpo = ByteArray((2 * mb).toInt())
        val cache = CacheMidia(contexto)
        cache.espacoTotal = { 1_000 * mb }
        // 1 MB acima da reserva: cabe a verificação de antes, não o vídeo.
        cache.espacoLivre = { cache.reservaBytes() + mb }

        val r = cache.resolucao(item("grande"))

        assertEquals(CacheMidia.Falha.SemEspaco, r.falha)
        assertFalse(cache.emCache(item("grande")))
        assertTrue(parciais().isEmpty())
    }

    @Test
    fun `disco que enche no meio do download interrompe e apaga o parcial`() {
        servidor.corpo = ByteArray((20 * mb).toInt())
        val cache = CacheMidia(contexto)
        cache.espacoTotal = { 1_000 * mb }
        // Outro processo enche o disco quando o download passa de 4 MB.
        cache.espacoLivre = {
            val baixado = parciais().sumOf { it.length() }
            if (baixado > 4 * mb) cache.reservaBytes() - 1 else 10_000 * mb
        }

        val r = cache.resolucao(item("enche"))

        assertEquals(CacheMidia.Falha.SemEspaco, r.falha)
        assertFalse(cache.emCache(item("enche")))
        assertTrue("parcial ficou no disco", parciais().isEmpty())
    }

    @Test
    fun `com folga, o mesmo download vai ao cache (controle)`() {
        servidor.corpo = ByteArray((20 * mb).toInt())
        val cache = CacheMidia(contexto)
        cache.espacoTotal = { 1_000 * mb }
        cache.espacoLivre = { 10_000 * mb }

        assertNotNull(cache.resolver(item("cabe")))
    }
}
