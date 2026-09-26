package br.com.mostrai.player.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.cache.ServidorDeTeste
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.estado.EstadoPlayer
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ciclo 3 — rotação de credencial com requisições em voo.
 *
 * Heartbeat, playlist, envio de proof-of-play e config rodam em threads
 * separadas. A candidata chega pela resposta de UMA delas enquanto as outras
 * já saíram com a chave antiga; a resposta delas não diz nada sobre a
 * candidata (contrato, seção 1.1: "primeira resposta bem-sucedida COM a
 * candidata").
 */
@RunWith(RobolectricTestRunner::class)
class RotacaoConcorrenciaTest {

    private lateinit var servidor: ServidorDeTeste
    private lateinit var config: ConfigAparelho
    private lateinit var api: MostraiApi

    @Before
    fun preparar() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        contexto.getSharedPreferences("mostrai_config", Context.MODE_PRIVATE).edit().clear().commit()
        servidor = ServidorDeTeste()
        config = ConfigAparelho(contexto).apply {
            baseUrl = servidor.baseUrl
            dispositivoId = "tela-1"
            chaveAparelho = "antiga"
        }
        api = MostraiApi(config)
    }

    @After
    fun encerrar() = servidor.encerrar()

    /** Dispara [chamada] com a chave antiga e injeta a candidata com ela em voo. */
    private fun comCandidataChegandoNoMeio(rota: String, resposta: ServidorDeTeste.Resposta, chamada: () -> Unit) {
        val trava = CountDownLatch(1)
        servidor.travas[rota] = trava
        servidor.rotas[rota] = resposta
        val voo = thread { chamada() }
        while (servidor.contar(rota) == 0) Thread.sleep(10)

        config.chaveCandidata = "candidata"
        trava.countDown()
        voo.join(5_000)
    }

    @Test
    fun `sucesso de requisicao feita com a chave antiga nao promove a candidata`() {
        // Promover sem prova joga fora a única chave que se sabe válida. Se a
        // candidata nunca foi gravada no servidor, a tela fica sem credencial
        // nenhuma — só volta com visita.
        comCandidataChegandoNoMeio("/playlist", ServidorDeTeste.Resposta(corpo = PLAYLIST.toByteArray())) {
            api.buscarPlaylist()
        }

        assertEquals("antiga", config.chaveAparelho)
        assertEquals("candidata", config.chaveCandidata)
    }

    @Test
    fun `recusa de requisicao feita com a chave antiga nao descarta a candidata`() {
        // A antiga pode ter sido revogada justamente porque a candidata a
        // substitui; descartar a candidata aqui deixaria a tela sem nenhuma.
        comCandidataChegandoNoMeio("/player", ServidorDeTeste.Resposta(codigo = 401)) {
            api.heartbeat(
                HeartbeatJson.Corpo(
                    estado = EstadoPlayer.PLAYING, configVersionAplicada = 0, criativoId = null,
                    ultimaPlaylistOkEm = null, filaPendentes = 0, filaMaisAntigoEm = null,
                    erroCodigo = null, erroEm = null, erroMensagem = null,
                    desvioRelogioMs = null,
                )
            )
        }

        assertEquals("candidata", config.chaveCandidata)
    }

    @Test
    fun `sucesso com a candidata promove`() {
        config.chaveCandidata = "candidata"
        servidor.rotas["/playlist"] = ServidorDeTeste.Resposta(corpo = PLAYLIST.toByteArray())

        api.buscarPlaylist()

        assertEquals("candidata", config.chaveAparelho)
        assertEquals(null, config.chaveCandidata)
    }

    private companion object {
        const val PLAYLIST = """{"versaoContrato":1,"janelaId":"j1","itens":[]}"""
    }

    @Test
    fun `baseUrl com barra final nao gera caminho com barra dupla`() {
        // Auditoria H: "https://api/" digitado no pendrive virava
        // "//playlist/..." — que frameworks como o Express não casam com
        // "/playlist/:id" (404), e a tela ficava em cache/institucional.
        config.baseUrl = servidor.baseUrl + "/"
        servidor.rotas["/playlist"] = ServidorDeTeste.Resposta(corpo = PLAYLIST.toByteArray())

        api.buscarPlaylist()

        assertEquals("GET /playlist/tela-1", servidor.recebidas.last())
    }
}
