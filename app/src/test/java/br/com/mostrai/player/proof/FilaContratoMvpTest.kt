package br.com.mostrai.player.proof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.network.ResultadoHttp
import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Proof-of-play durável contra as regras do contrato §8. */
@RunWith(RobolectricTestRunner::class)
class FilaContratoMvpTest {

    /** Responde a cada lote com o que [responder] decidir, e guarda o que foi enviado. */
    private class Servidor(config: ConfigAparelho) : MostraiApi(config) {
        var responder: (List<EventoExibicao>) -> ResultadoHttp<Map<String, String>> =
            { lote -> ResultadoHttp.Ok(lote.associate { it.execucaoId to "contabilizado" }) }
        val lotes = mutableListOf<List<String>>()

        override fun enviarLote(eventos: List<EventoExibicao>): ResultadoHttp<Map<String, String>> {
            lotes += eventos.map { it.execucaoId }
            return responder(eventos)
        }
    }

    private lateinit var contexto: Context
    private lateinit var db: ProofOfPlayDb
    private lateinit var api: Servidor
    private lateinit var fila: FilaProofOfPlay

    private val item = ItemPlaylist("235|j|0|17", "88", 15, "https://x/88.mp4", contabiliza = true)
    private val playlist = Playlist("235|j", null, null, listOf(item))

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        contexto.deleteDatabase(ProofOfPlayDb.NOME_ARQUIVO)
        contexto.getSharedPreferences(ProofOfPlayDb.PREFS_PERDAS, Context.MODE_PRIVATE).edit().clear().commit()
        val config = ConfigAparelho(contexto)
        db = ProofOfPlayDb(contexto)
        api = Servidor(config)
        fila = FilaProofOfPlay(contexto, api)
    }

    private fun comprovante(id: String, idadeMs: Long) = db.inserir(
        EventoExibicao(
            execucaoId = id, janelaId = "235|j", itemProgramacaoId = "235|j|0|17", criativoId = "88",
            iniciadoEm = "2026-09-26T10:00:00-03:00", terminadoEm = "2026-09-26T10:00:15-03:00",
            tentativas = 0, proximoEnvioElegivelEm = 0L, criadoEmMs = System.currentTimeMillis() - idadeMs,
        )
    )

    private val hora = 60 * 60 * 1000L
    private val dia = 24 * hora

    @Test
    fun `evento atrasado ainda e enviado, de imediato ate 7 dias`() {
        listOf(0L, 2 * hora, 8 * hora, 24 * hora, 6 * dia, 7 * dia).forEachIndexed { i, idade ->
            comprovante("e$i", idade)
        }

        fila.tentarEnviar()

        assertEquals(6, api.lotes.flatten().size)
        assertEquals(0, db.contarPendentes())
        assertEquals(0, fila.perdas())
    }

    @Test
    fun `depois de 7 dias e 1 hora o evento expira sem ser enviado e conta como perda`() {
        comprovante("velho", 7 * dia + hora + 60_000L)
        comprovante("novo", 0L)

        fila.tentarEnviar()

        assertEquals(listOf(listOf("novo")), api.lotes)
        assertEquals(1, fila.perdas())
    }

    @Test
    fun `os 6 status finais tiram o evento da fila`() {
        val status = listOf("contabilizado", "duplicado", "teto_atingido", "janela_desconhecida", "janela_expirada", "item_invalido")
        status.forEach { comprovante(it, 0L) }
        api.responder = { lote -> ResultadoHttp.Ok(lote.associate { it.execucaoId to it.execucaoId }) }

        fila.tentarEnviar()

        assertEquals(0, db.contarPendentes())
    }

    @Test
    fun `retentativa usa o mesmo execucaoId`() {
        val id = fila.registrarInicio(item, playlist)!!
        fila.registrarFim(id)
        api.responder = { ResultadoHttp.ErroServidor(503) }
        fila.tentarEnviar()
        db.adiarReenvio(id, 0L, 1)

        api.responder = { lote -> ResultadoHttp.Ok(lote.associate { it.execucaoId to "duplicado" }) }
        fila.tentarEnviar()

        assertEquals(listOf(listOf(id), listOf(id)), api.lotes)
        assertEquals(0, db.contarPendentes())
    }

    @Test
    fun `exibicao interrompida nunca vira comprovante`() {
        val parcial = fila.registrarInicio(item, playlist)!!
        fila.tentarEnviar()
        assertTrue("evento sem STATE_ENDED foi enviado", api.lotes.isEmpty())

        fila.registrarFalha(parcial)
        assertEquals(0, db.contarPendentes())
    }

    @Test
    fun `413 divide o lote como o 400 e isola so o culpado`() {
        (1..4).forEach { comprovante("e$it", 0L) }
        api.responder = { lote ->
            if (lote.any { it.execucaoId == "e3" }) {
                ResultadoHttp.RespostaInvalida("HTTP 413")
            } else {
                ResultadoHttp.Ok(lote.associate { it.execucaoId to "contabilizado" })
            }
        }

        fila.tentarEnviar()

        assertEquals(1, db.contarQuarentena())
        assertEquals(0, db.contarAguardandoEnvio())
    }

    @Test
    fun `403, 401, 5xx e rede mantem o comprovante na fila`() {
        comprovante("e1", 0L)
        listOf<ResultadoHttp<Map<String, String>>>(
            ResultadoHttp.TelaSuspensa, ResultadoHttp.CredencialRecusada(401),
            ResultadoHttp.ErroServidor(500), ResultadoHttp.SemRede("timeout"),
        ).forEach { resposta ->
            api.responder = { resposta }
            db.adiarReenvio("e1", 0L, 0)
            fila.tentarEnviar()
            assertEquals("depois de $resposta", 1, db.contarAguardandoEnvio())
        }
        assertEquals(0, fila.perdas())
    }

    @Test
    fun `item que nao conta nunca entra na fila`() {
        assertEquals(null, fila.registrarInicio(item.copy(contabiliza = false), playlist))
    }

    @Test
    fun `evento leva janelaId e itemProgramacaoId exatamente como vieram na playlist`() {
        val id = fila.registrarInicio(item, playlist)!!
        fila.registrarFim(id)
        val enviado = db.elegiveisParaEnvio(System.currentTimeMillis(), 10).single()

        assertEquals("235|j", enviado.janelaId)
        assertEquals("235|j|0|17", enviado.itemProgramacaoId)
        assertEquals("88", enviado.criativoId)
    }
}
