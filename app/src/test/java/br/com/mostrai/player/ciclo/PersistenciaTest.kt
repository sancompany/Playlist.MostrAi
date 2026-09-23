package br.com.mostrai.player.ciclo

import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.estado.DiarioBordo
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import br.com.mostrai.player.proof.FilaProofOfPlay
import br.com.mostrai.player.proof.ProofOfPlayDb
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode

/**
 * Ciclo 3 — o banco local some debaixo do player.
 *
 * Disco cheio (`SQLiteFullException`), arquivo que não abre
 * (`SQLiteCantOpenDatabaseException`) e E/S falhando
 * (`SQLiteDiskIOException`) são todos `SQLiteException`. O jeito
 * reproduzível de provocar a família no Robolectric é pôr um diretório no
 * caminho do arquivo do banco: nenhuma abertura funciona.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PersistenciaTest {

    private lateinit var h: Harness
    private val naoCapturadas = Collections.synchronizedList(mutableListOf<Throwable>())
    private var tratadorAnterior: Thread.UncaughtExceptionHandler? = null

    @Before
    fun preparar() {
        h = Harness()
        // Exceção não capturada numa corrotina de lifecycleScope vai para o
        // tratador da thread — no aparelho, isso mata o processo.
        tratadorAnterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> naoCapturadas.add(e) }
    }

    @After
    fun encerrar() {
        Thread.setDefaultUncaughtExceptionHandler(tratadorAnterior)
        listOf(DiarioBordo.NOME_ARQUIVO, ProofOfPlayDb.NOME_ARQUIVO).forEach {
            h.contexto.getDatabasePath(it).deleteRecursively()
        }
        h.encerrar()
    }

    private fun inutilizar(nomeBanco: String) {
        val caminho = h.contexto.getDatabasePath(nomeBanco)
        caminho.deleteRecursively()
        check(caminho.mkdirs()) { "não consegui bloquear $caminho" }
    }

    private val item = ItemPlaylist(
        itemProgramacaoId = "i1", criativoId = "c1", duracaoSegundos = 10,
        url = "https://exemplo.com/v.mp4", anuncianteId = "a1",
        autoanuncio = false, institucional = false, contabiliza = true,
    )

    @Test
    fun `diario inutilizavel nao derruba o boot`() {
        // DiarioBordo.registrar(BOOT) roda dentro de onCreate. Se lançar, o
        // app morre no boot, o watchdog reabre, e morre de novo: tela preta
        // em laço até alguém ir na loja — por causa do DIAGNÓSTICO.
        inutilizar(DiarioBordo.NOME_ARQUIVO)

        h.subir()

        assertTrue("exceção não capturada: $naoCapturadas", naoCapturadas.isEmpty())
    }

    @Test
    fun `fila inutilizavel toca sem cobrar em vez de derrubar o player`() {
        inutilizar(ProofOfPlayDb.NOME_ARQUIVO)
        h.provisionar()
        h.servidor.rotas["/playlist"] = ServidorDeTeste.Resposta(corpo = h.playlistComUmVideo().toByteArray())
        h.servidor.rotas["/midia"] = ServidorDeTeste.Resposta(corpo = "bytes".toByteArray())

        h.subir()
        h.esperar { h.servidor.contar("/midia") > 0 }
        repeat(25) {
            Thread.sleep(20)
            h.idle()
        }

        assertTrue("exceção não capturada: $naoCapturadas", naoCapturadas.isEmpty())
    }

    @Test
    fun `fila inutilizavel nao lanca em nenhuma operacao`() {
        inutilizar(ProofOfPlayDb.NOME_ARQUIVO)
        val config = ConfigAparelho(h.contexto).apply {
            baseUrl = h.servidor.baseUrl
            dispositivoId = "tela-1"
            chaveAparelho = "chave"
        }
        val fila = FilaProofOfPlay(h.contexto, MostraiApi(config))
        val playlist = Playlist(
            versaoContrato = 1, janelaId = "j1", janelaInicio = null, janelaFim = null,
            servidorAgora = null, itens = listOf(item),
        )

        assertNull(fila.registrarInicio(item, playlist))
        fila.registrarFim("x")
        fila.registrarFalha("x")
        fila.tentarEnviar()
        assertEquals(0, fila.pendentes())
        fila.resumo()
    }

    @Test
    fun `diario inutilizavel nao lanca em nenhuma operacao`() {
        inutilizar(DiarioBordo.NOME_ARQUIVO)
        val diario = DiarioBordo(h.contexto)

        diario.registrar(DiarioBordo.Codigo.BOOT)
        diario.limparErros()
        assertNull(diario.ultimoErro())
        assertEquals(0, diario.ultimos(10).size)
    }
}
