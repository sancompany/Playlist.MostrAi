package br.com.mostrai.player.proof

import android.content.Context
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

/**
 * Ciclo 4 — "eventos perdidos" conta comprovante perdido, e só uma vez.
 * É o número que o painel mostra para quem precisa decidir se houve receita
 * jogada fora.
 */
@RunWith(RobolectricTestRunner::class)
class FilaPerdasTest {

    private class Api(config: ConfigAparelho) : MostraiApi(config) {
        var resposta: RespostaPlayed = RespostaPlayed.Transitorio("x")
        var envios = 0
        override fun enviarLote(eventos: List<EventoExibicao>): RespostaPlayed {
            envios++
            return resposta
        }
    }

    private lateinit var contexto: Context
    private lateinit var api: Api
    private lateinit var fila: FilaProofOfPlay

    private val playlist = Playlist(
        versaoContrato = 1, janelaId = "j1", janelaInicio = null, janelaFim = null,
        servidorAgora = null, itens = emptyList(),
    )
    private val item = ItemPlaylist(
        itemProgramacaoId = "i1", criativoId = "c1", duracaoSegundos = 10, url = "https://x/v.mp4",
        anuncianteId = "a1", autoanuncio = false, institucional = false, contabiliza = true,
    )

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        contexto.deleteDatabase(ProofOfPlayDb.NOME_ARQUIVO)
        contexto.getSharedPreferences(ProofOfPlayDb.PREFS_PERDAS, Context.MODE_PRIVATE).edit().clear().commit()
        api = Api(ConfigAparelho(contexto))
        fila = FilaProofOfPlay(contexto, api)
    }

    private fun envelhecerTudo() {
        ProofOfPlayDb(contexto).writableDatabase.execSQL("UPDATE ${ProofOfPlayDb.TABELA} SET criado_em_ms = 1")
    }

    @Test
    fun `expirar orfao e quarentena nao conta perda de novo`() {
        // Quarentena: o 400 já contou a perda na hora. Órfão: exibição que
        // nunca terminou (queda de energia no meio do anúncio) — nunca foi
        // comprovante. Nenhum dos dois pode somar de novo ao expirar.
        val rejeitado = fila.registrarInicio(item, playlist)!!
        fila.registrarFim(rejeitado)
        api.resposta = MostraiApi.RespostaPlayed.ErroPayload(400)
        fila.tentarEnviar()
        assertEquals(1, fila.perdas())

        fila.registrarInicio(item, playlist) // órfão
        envelhecerTudo()

        fila.tentarEnviar()

        assertEquals(1, fila.perdas())
        assertEquals(0, fila.resumo().total)
    }

    @Test
    fun `fila cheia que descarta um orfao nao conta perda`() {
        val db = ProofOfPlayDb(contexto).writableDatabase
        db.beginTransaction()
        try {
            val insert = db.compileStatement(
                "INSERT INTO ${ProofOfPlayDb.TABELA} (execucao_id, formato_legado, iniciado_em, criado_em_ms) " +
                    "VALUES (?, 0, 'x', ?)",
            )
            val agora = System.currentTimeMillis()
            repeat(FilaProofOfPlay.TAMANHO_MAXIMO_FILA) {
                insert.bindString(1, "orfao-$it")
                insert.bindLong(2, agora)
                insert.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        fila.registrarInicio(item, playlist)

        assertEquals(0, fila.perdas())
    }

    @Test
    fun `retry-after absurdo nao empurra o comprovante para alem da expiracao`() {
        // Um proxy/CDN mal configurado responde 429 com Retry-After enorme.
        // Obedecer cegamente adia o lote para depois do horizonte de 7 dias:
        // o comprovante expira sem nunca ter sido reenviado.
        val id = fila.registrarInicio(item, playlist)!!
        fila.registrarFim(id)
        api.resposta = MostraiApi.RespostaPlayed.RespeitarEspera(1_000_000_000)

        fila.tentarEnviar()

        val daquiAUmDia = System.currentTimeMillis() + 24 * 60 * 60 * 1000L
        val elegiveis = ProofOfPlayDb(contexto).elegiveisParaEnvio(daquiAUmDia, 10)
        assertEquals("evento adiado para além de um dia", 1, elegiveis.size)
    }

    @Test
    fun `credencial recusada nao reenvia o lote a cada exibicao`() {
        // tentarEnviar roda ao fim de TODA exibição e a cada minuto. Com a
        // credencial recusada, sem reagendar, o lote inteiro ia de novo a
        // cada 10-30s só para voltar 401 — e os comprovantes ficam intactos.
        val id = fila.registrarInicio(item, playlist)!!
        fila.registrarFim(id)
        api.resposta = MostraiApi.RespostaPlayed.ErroAparelho(401)

        repeat(5) { fila.tentarEnviar() }

        assertEquals(1, api.envios)
        assertEquals(1, fila.pendentes())
        assertEquals(0, fila.perdas())
    }

    @Test
    fun `comprovante de dois dias continua na fila`() {
        // Horizonte é de 7 dias (feriado prolongado offline). Um horizonte
        // menor jogaria fora receita recuperável (mutação M12).
        val id = fila.registrarInicio(item, playlist)!!
        fila.registrarFim(id)
        val doisDias = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
        ProofOfPlayDb(contexto).writableDatabase.execSQL(
            "UPDATE ${ProofOfPlayDb.TABELA} SET criado_em_ms = $doisDias",
        )

        fila.tentarEnviar()

        assertEquals(1, fila.pendentes())
        assertEquals(0, fila.perdas())
    }

    @Test
    fun `retentativa leva o mesmo execucaoId`() {
        // Invariante 3: o servidor deduplica por execucaoId. Um id novo na
        // retentativa viraria cobrança dupla da mesma exibição.
        val ids = mutableListOf<List<String>>()
        val espiao = object : MostraiApi(ConfigAparelho(contexto)) {
            override fun enviarLote(eventos: List<EventoExibicao>): RespostaPlayed {
                ids += eventos.map { it.execucaoId }
                return RespostaPlayed.Transitorio("x")
            }
        }
        val filaEspia = FilaProofOfPlay(contexto, espiao)
        val id = filaEspia.registrarInicio(item, playlist)!!
        filaEspia.registrarFim(id)

        filaEspia.tentarEnviar()
        ProofOfPlayDb(contexto).writableDatabase.execSQL("UPDATE ${ProofOfPlayDb.TABELA} SET proximo_envio_em = 0")
        filaEspia.tentarEnviar()

        assertEquals(listOf(listOf(id), listOf(id)), ids)
    }

    @Test
    fun `comprovante terminado que expira sem envio conta como perda`() {
        val id = fila.registrarInicio(item, playlist)!!
        fila.registrarFim(id)
        envelhecerTudo()

        fila.tentarEnviar()

        assertEquals(1, fila.perdas())
    }
}
