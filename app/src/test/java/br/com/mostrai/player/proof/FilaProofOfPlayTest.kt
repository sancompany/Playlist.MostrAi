package br.com.mostrai.player.proof

import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Testa o ciclo de vida da fila contra o contrato fechado na seção 6.5:
 * um evento só sai da fila em três casos (status definitivo, payload
 * malformado, expiração) — nunca por timeout, 5xx ou reinício.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class FilaProofOfPlayTest {

    private lateinit var fila: FilaProofOfPlay
    private lateinit var api: ApiDeMentira

    private val playlist = Playlist(
        versaoContrato = 1,
        janelaId = "janela-1",
        janelaInicio = "2026-09-21T13:00:00-03:00",
        janelaFim = "2026-09-21T14:00:00-03:00",
        servidorAgora = "2026-09-21T13:05:00-03:00",
        itens = emptyList(),
    )

    private val item = ItemPlaylist(
        itemProgramacaoId = "slot-1",
        criativoId = "crv-1",
        duracaoSegundos = 15,
        url = "https://x/a.mp4",
        anuncianteId = "anun-1",
        autoanuncio = false,
        institucional = false,
        contabiliza = true,
    )

    /** Dublê de MostraiApi — respostas canônicas, sem tocar rede de verdade. */
    private class ApiDeMentira(config: ConfigAparelho) : MostraiApi(config) {
        var proximaResposta: MostraiApi.RespostaPlayed = MostraiApi.RespostaPlayed.Transitorio("nao configurado")
        override fun enviarLote(eventos: List<EventoExibicao>): MostraiApi.RespostaPlayed = proximaResposta
    }

    @Before
    fun setUp() {
        val contexto = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = ConfigAparelho(contexto)
        api = ApiDeMentira(config)
        fila = FilaProofOfPlay(contexto, api)
    }

    private fun criarEExpirar(id: String): String {
        val execucaoId = fila.registrarInicio(item, playlist)!!
        fila.registrarFim(execucaoId)
        return execucaoId
    }

    @Test
    fun `item institucional nunca entra na fila`() {
        val institucional = item.copy(institucional = true, contabiliza = false, url = null)
        val id = fila.registrarInicio(institucional, playlist)

        assertEquals(null, id)
        assertEquals(0, fila.pendentes())
    }

    @Test
    fun `status definitivo remove da fila`() {
        val id = criarEExpirar("e1")
        api.proximaResposta = MostraiApi.RespostaPlayed.Sucesso(mapOf(id to "contabilizado"))

        fila.tentarEnviar()

        assertEquals(0, fila.pendentes())
    }

    @Test
    fun `duplicado tambem remove, nao e erro`() {
        val id = criarEExpirar("e2")
        api.proximaResposta = MostraiApi.RespostaPlayed.Sucesso(mapOf(id to "duplicado"))

        fila.tentarEnviar()

        assertEquals(0, fila.pendentes())
    }

    @Test
    fun `payload malformado remove e conta como perda`() {
        criarEExpirar("e3")
        api.proximaResposta = MostraiApi.RespostaPlayed.ErroPayload(400)

        fila.tentarEnviar()

        assertEquals(0, fila.pendentes())
        assertEquals(1, fila.perdas())
    }

    @Test
    fun `erro do aparelho mantem a fila intacta`() {
        criarEExpirar("e4")
        api.proximaResposta = MostraiApi.RespostaPlayed.ErroAparelho(401)

        fila.tentarEnviar()

        assertEquals(1, fila.pendentes())
        assertEquals(0, fila.perdas())
    }

    @Test
    fun `falha transitoria mantem a fila intacta, sem contar perda`() {
        criarEExpirar("e5")
        api.proximaResposta = MostraiApi.RespostaPlayed.Transitorio("timeout")

        fila.tentarEnviar()

        assertEquals(1, fila.pendentes())
        assertEquals(0, fila.perdas())
    }

    @Test
    fun `reproducao que falha antes de terminar nao conta como perda`() {
        val id = fila.registrarInicio(item, playlist)!!

        fila.registrarFalha(id)

        assertEquals(0, fila.pendentes())
        assertEquals(0, fila.perdas())
    }

    @Test
    fun `linha sem terminadoEm nao e enviada mesmo com tentarEnviar chamado`() {
        fila.registrarInicio(item, playlist)
        api.proximaResposta = MostraiApi.RespostaPlayed.Sucesso(emptyMap())

        fila.tentarEnviar()

        // continua pendente: nunca ficou elegível porque não terminou.
        assertEquals(1, fila.pendentes())
    }
}
