package br.com.mostrai.player.ciclo

import android.content.Context
import android.net.ConnectivityManager
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.estado.EstadoPlayer
import br.com.mostrai.player.proof.EventoExibicao
import br.com.mostrai.player.proof.ProofOfPlayDb
import br.com.mostrai.player.ui.EstadoInstitucional
import br.com.mostrai.player.ui.TelaInstitucional
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/** "Internet caiu ≠ tela parou" (contrato §7). */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OfflineCicloTest {

    private lateinit var h: Harness

    @Before
    fun preparar() {
        h = Harness()
        h.provisionar()
    }

    @After
    fun encerrar() = h.encerrar()

    private fun estado(atividade: br.com.mostrai.player.PlayerActivity) = h.campo<EstadoPlayer>(atividade, "estadoAtual")

    private fun comprovantePendente() = ProofOfPlayDb(h.contexto).inserir(
        EventoExibicao(
            "exec-1", "j1", "i1", "c1", "2026-09-26T10:00:00-03:00", "2026-09-26T10:00:10-03:00",
            tentativas = 0, proximoEnvioElegivelEm = 0L, criadoEmMs = System.currentTimeMillis(),
        )
    )

    @Test
    fun `queda antes da primeira playlist mostra sem conteudo e tenta de novo sozinho`() {
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(503, "")
        val atividade = h.subir().get()
        h.esperar { h.servidor.contar("/playlist/") == 1 && estado(atividade) == EstadoPlayer.NO_PLAYLIST }

        assertEquals(EstadoInstitucional.SEM_CONTEUDO, h.campo<TelaInstitucional>(atividade, "institucional").estado)

        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo())
        h.midiaNoAr()
        h.avancar(61_000L)
        h.esperar { estado(atividade) == EstadoPlayer.PLAYING }
    }

    @Test
    fun `sem rede depois de reiniciar, toca a ultima playlist valida do cache`() {
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo(duracao = 600))
        h.midiaNoAr()
        val primeira = h.subir()
        h.esperar { estado(primeira.get()) == EstadoPlayer.PLAYING }
        primeira.pause().stop().destroy()

        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(503, "")
        h.midiaNoAr()
        val segunda = h.subir().get()

        h.esperar { estado(segunda) == EstadoPlayer.PLAYING }
        assertEquals(
            br.com.mostrai.player.playlist.PlaylistRepositorio.Origem.CACHE,
            h.campo<Any>(segunda, "ultimaOrigemFetch"),
        )
    }

    @Test
    fun `comprovante fica na fila sem rede e sai quando a rede volta`() {
        comprovantePendente()
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(200, h.playlistComUmVideo(duracao = 600))
        h.midiaNoAr()
        h.servidor.rotas["/player/M-0001/played"] = ServidorDeTeste.Resposta(503, "")
        h.subir()
        h.esperar { h.servidor.contar("/player/M-0001/played") >= 1 }
        assertEquals(1, ProofOfPlayDb(h.contexto).contarAguardandoEnvio())

        h.servidor.rotas["/player/M-0001/played"] =
            ServidorDeTeste.Resposta(200, """{"resultados":[{"execucaoId":"exec-1","status":"contabilizado"}]}""")
        // O backoff (relógio de parede, que o looper do teste não avança)
        // já passou quando a rede "volta".
        ProofOfPlayDb(h.contexto).adiarReenvio("exec-1", 0L, 1)
        val conectividade = h.contexto.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(conectividade).networkCallbacks.forEach { it.onAvailable(conectividade.activeNetwork!!) }

        h.esperar { ProofOfPlayDb(h.contexto).contarAguardandoEnvio() == 0 }
    }

    @Test
    fun `tela em reparo (403) para os anuncios e mantem a fila`() {
        comprovantePendente()
        h.servidor.rotas["/playlist/"] = ServidorDeTeste.Resposta(403, """{"erro":"fora do ar"}""")
        h.servidor.rotas["/player/M-0001/played"] = ServidorDeTeste.Resposta(403, "")
        val atividade = h.subir().get()

        h.esperar { h.servidor.contar("/playlist/") == 1 && estado(atividade) == EstadoPlayer.IDLE }
        h.esperar { h.servidor.contar("/player/M-0001/played") >= 1 }

        assertEquals(EstadoInstitucional.CARTAO, h.campo<TelaInstitucional>(atividade, "institucional").estado)
        assertEquals(1, ProofOfPlayDb(h.contexto).contarAguardandoEnvio())
        assertEquals(
            "playlist guardada apagada",
            null,
            h.contexto.getSharedPreferences("mostrai_cache_playlist", Context.MODE_PRIVATE).getString("corpo_bruto", null),
        )
    }
}
