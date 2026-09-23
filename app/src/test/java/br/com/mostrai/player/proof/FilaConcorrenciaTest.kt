package br.com.mostrai.player.proof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Ciclo 2 — a fila sob chamadas simultâneas. */
@RunWith(RobolectricTestRunner::class)
class FilaConcorrenciaTest {

    /** Envio que fica preso até o teste liberar, e conta quantos lotes chegaram. */
    private class ApiLenta(config: ConfigAparelho) : MostraiApi(config) {
        val liberar = CountDownLatch(1)
        val entrouNoEnvio = CountDownLatch(1)
        val enviados = AtomicInteger()
        val idsEnviados = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun enviarLote(eventos: List<EventoExibicao>): RespostaPlayed {
            entrouNoEnvio.countDown()
            liberar.await(10, TimeUnit.SECONDS)
            enviados.incrementAndGet()
            idsEnviados += eventos.map { it.execucaoId }
            return RespostaPlayed.Sucesso(eventos.associate { it.execucaoId to "contabilizado" })
        }
    }

    private lateinit var contexto: Context
    private lateinit var api: ApiLenta
    private lateinit var fila: FilaProofOfPlay

    private val item = ItemPlaylist(
        itemProgramacaoId = "i1", criativoId = "c1", duracaoSegundos = 10,
        url = "https://exemplo.com/v.mp4", anuncianteId = "a1",
        autoanuncio = false, institucional = false, contabiliza = true,
    )
    private val playlist = Playlist(1, "j1", null, null, null, listOf(item))

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        contexto.deleteDatabase(ProofOfPlayDb.NOME_ARQUIVO)
        api = ApiLenta(ConfigAparelho(contexto))
        fila = FilaProofOfPlay(contexto, api)
    }

    @Test
    fun `envio lento nao trava o inicio da proxima exibicao`() {
        // Depois de CADA exibição o player chama registrarFim + tentarEnviar,
        // e logo em seguida o próximo item chama registrarInicio. Se o envio
        // segura a trava durante a chamada de rede, todo item novo espera o
        // round-trip do anterior — e numa internet de comércio que está
        // dando timeout, isso é até 25s de tela congelada por transição.
        val id = fila.registrarInicio(item, playlist)!!
        fila.registrarFim(id)
        val envio = thread { fila.tentarEnviar() }
        assertTrue(api.entrouNoEnvio.await(5, TimeUnit.SECONDS))

        val inicio = System.nanoTime()
        val proximo = fila.registrarInicio(item, playlist)
        val esperouMs = (System.nanoTime() - inicio) / 1_000_000

        api.liberar.countDown()
        envio.join(5_000)

        assertTrue("registrarInicio esperou ${esperouMs}ms pela rede", esperouMs < 1_000)
        assertTrue(proximo != null)
    }

    @Test
    fun `dois envios simultaneos nao mandam o mesmo evento duas vezes`() {
        val id = fila.registrarInicio(item, playlist)!!
        fila.registrarFim(id)

        val primeiro = thread { fila.tentarEnviar() }
        assertTrue(api.entrouNoEnvio.await(5, TimeUnit.SECONDS))
        val segundo = thread { fila.tentarEnviar() }
        Thread.sleep(200)
        api.liberar.countDown()
        primeiro.join(5_000)
        segundo.join(5_000)

        assertEquals("o mesmo execucaoId foi enviado mais de uma vez", 1, api.idsEnviados.count { it == id })
    }
}
