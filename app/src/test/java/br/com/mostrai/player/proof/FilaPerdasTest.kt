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
        override fun enviarLote(eventos: List<EventoExibicao>) = resposta
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
    fun `comprovante terminado que expira sem envio conta como perda`() {
        val id = fila.registrarInicio(item, playlist)!!
        fila.registrarFim(id)
        envelhecerTudo()

        fila.tentarEnviar()

        assertEquals(1, fila.perdas())
    }
}
