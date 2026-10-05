package br.com.mostrai.player.proof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.network.ResultadoHttp
import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * V1 de produção (05/10/2026): a fila de comprovantes com o relógio de
 * parede da TV mentindo — TV sem bateria no relógio que liga em 2020, NTP
 * que chega horas depois e salta o relógio para frente ou para trás.
 */
@RunWith(RobolectricTestRunner::class)
class FilaRelogioHostilTest {

    private class Api(config: ConfigAparelho) : MostraiApi(config) {
        val enviados = mutableListOf<EventoExibicao>()
        override fun enviarLote(eventos: List<EventoExibicao>): ResultadoHttp<Map<String, String>> {
            enviados += eventos
            return ResultadoHttp.Ok(eventos.associate { it.execucaoId to "contabilizado" })
        }
    }

    private lateinit var contexto: Context
    private lateinit var api: Api

    private val playlist = Playlist(janelaId = "j1", janelaInicio = null, servidorAgora = null, itens = emptyList())
    private val item = ItemPlaylist(
        itemProgramacaoId = "i1", criativoId = "c1", duracaoSegundos = 10, url = "https://x/v.mp4", contabiliza = true,
    )

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        contexto.deleteDatabase(ProofOfPlayDb.NOME_ARQUIVO)
        api = Api(ConfigAparelho(contexto))
    }

    private fun db() = ProofOfPlayDb(contexto).writableDatabase

    @Test
    fun `espera de reenvio marcada com o relogio adiantado nao prende o comprovante por anos`() {
        val fila = FilaProofOfPlay(contexto, api)
        val id = fila.registrarInicio(item, playlist)!!
        fila.registrarFim(id)
        // O backoff foi marcado quando a TV achava que era 2031; o NTP trouxe
        // de volta para hoje. Nenhuma espera legítima passa de 30 min.
        db().execSQL(
            "UPDATE ${ProofOfPlayDb.TABELA} SET proximo_envio_em = ?, tentativas = 3",
            arrayOf<Any>(System.currentTimeMillis() + 5L * 365 * 24 * 3_600_000),
        )

        fila.tentarEnviar()

        assertEquals(listOf(id), api.enviados.map { it.execucaoId })
        assertEquals(0, ProofOfPlayDb(contexto).contarPendentes())
    }

    @Test
    fun `espera legitima de backoff ainda e respeitada`() {
        val fila = FilaProofOfPlay(contexto, api)
        val id = fila.registrarInicio(item, playlist)!!
        fila.registrarFim(id)
        db().execSQL(
            "UPDATE ${ProofOfPlayDb.TABELA} SET proximo_envio_em = ?",
            arrayOf<Any>(System.currentTimeMillis() + 29L * 60_000),
        )

        fila.tentarEnviar()

        assertTrue("backoff de 29 min furado", api.enviados.isEmpty())
    }

    @Test
    fun `exibicao no ar nao some na limpeza por idade quando o relogio salta dias para frente`() {
        val fila = FilaProofOfPlay(contexto, api)
        val noAr = fila.registrarInicio(item, playlist)!!
        // Órfão de verdade, de um processo anterior.
        val orfao = FilaProofOfPlay(contexto, api).registrarInicio(item, playlist)!!
        // O relógio de parede saltou 8 dias depois do início: as duas linhas
        // parecem velhas para a limpeza.
        db().execSQL("UPDATE ${ProofOfPlayDb.TABELA} SET criado_em_ms = 1")

        fila.tentarEnviar() // roda a limpeza
        fila.registrarFim(noAr)
        fila.tentarEnviar()

        assertEquals("o anúncio exibido inteiro perdeu o comprovante", listOf(noAr), api.enviados.map { it.execucaoId })
        assertTrue(orfao !in api.enviados.map { it.execucaoId })
    }

    @Test
    fun `fila cheia nunca escolhe para descarte a exibicao no ar`() {
        val fila = FilaProofOfPlay(contexto, api)
        val noAr = fila.registrarInicio(item, playlist)!!

        assertNull(ProofOfPlayDb(contexto).proximoADescartar(listOf(noAr)))
        assertEquals(noAr, ProofOfPlayDb(contexto).proximoADescartar())
    }

    @Test
    fun `inicio e fim do comprovante vao no relogio confiavel, nao no da TV`() {
        val servidor = OffsetDateTime.parse("2026-10-05T14:00:00-03:00").toInstant().toEpochMilli()
        var agora = servidor
        val fila = FilaProofOfPlay(contexto, api) { agora }
        val id = fila.registrarInicio(item, playlist)!!
        agora += 10_000L
        fila.registrarFim(id)

        fila.tentarEnviar()

        val enviado = api.enviados.single()
        assertEquals(servidor, OffsetDateTime.parse(enviado.iniciadoEm).toInstant().toEpochMilli())
        assertEquals(servidor + 10_000L, OffsetDateTime.parse(enviado.terminadoEm).toInstant().toEpochMilli())
    }
}
