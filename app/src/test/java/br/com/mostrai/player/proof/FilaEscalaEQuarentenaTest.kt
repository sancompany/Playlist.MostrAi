package br.com.mostrai.player.proof

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.com.mostrai.player.config.ConfigAparelho
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** R1 (teto), R2 (ordem de descarte) e R4 (split do 400 + quarentena). */
@RunWith(RobolectricTestRunner::class)
class FilaEscalaEQuarentenaTest {

    /** Rejeita exatamente um execucaoId com 400; o resto passa. */
    private class ApiSeletiva(config: ConfigAparelho) : MostraiApi(config) {
        var idRuim: String? = null
        var lotesEnviados = mutableListOf<Int>()

        override fun enviarLote(eventos: List<EventoExibicao>): RespostaPlayed {
            lotesEnviados += eventos.size
            val ruim = idRuim
            return if (ruim != null && eventos.any { it.execucaoId == ruim }) {
                RespostaPlayed.ErroPayload(400)
            } else {
                RespostaPlayed.Sucesso(eventos.associate { it.execucaoId to "contabilizado" })
            }
        }
    }

    private lateinit var contexto: Context
    private lateinit var db: ProofOfPlayDb
    private lateinit var api: ApiSeletiva
    private lateinit var fila: FilaProofOfPlay

    private val item = ItemPlaylist(
        itemProgramacaoId = "i1",
        criativoId = "c1",
        duracaoSegundos = 10,
        url = "https://exemplo.com/v.mp4",
        anuncianteId = "a1",
        autoanuncio = false,
        institucional = false,
        contabiliza = true,
    )
    private val playlist = Playlist(1, "janela-1", null, null, null, listOf(item))

    @Before
    fun preparar() {
        contexto = ApplicationProvider.getApplicationContext()
        contexto.deleteDatabase(ProofOfPlayDb.NOME_ARQUIVO)
        contexto.getSharedPreferences(ProofOfPlayDb.PREFS_PERDAS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        db = ProofOfPlayDb(contexto)
        api = ApiSeletiva(ConfigAparelho(contexto))
        fila = FilaProofOfPlay(contexto, api)
    }

    // ------------------------------------------------------------------- R1

    @Test
    fun `o teto cobre mais de um dia offline com criativos de 10s`() {
        // Uma tela 24h com criativos de 10s gera 8.640 eventos por dia. O
        // teto anterior (5.000) saturava em 13,9h — menos de um dia.
        val eventosPorDia = 24 * 60 * 60 / 10
        assertTrue(
            "teto precisa cobrir ao menos 24h de tela com criativos de 10s",
            FilaProofOfPlay.TAMANHO_MAXIMO_FILA > eventosPorDia,
        )
        assertEquals(50_000, FilaProofOfPlay.TAMANHO_MAXIMO_FILA)
    }

    @Test
    fun `limiares de alerta ficam abaixo do teto`() {
        assertTrue(FilaProofOfPlay.LIMIAR_ATENCAO < FilaProofOfPlay.LIMIAR_ALERTA)
        assertTrue(FilaProofOfPlay.LIMIAR_ALERTA < FilaProofOfPlay.TAMANHO_MAXIMO_FILA)
    }

    // ------------------------------------------------------------------- R4

    @Test
    fun `um evento ruim nao leva o lote inteiro junto`() {
        val ids = (1..8).map { fila.registrarInicio(item, playlist)!! }
        ids.forEach { fila.registrarFim(it) }
        api.idRuim = ids[3]

        fila.tentarEnviar()

        val resumo = fila.resumo()
        // Os sete bons foram entregues e removidos; só o ruim sobra, em
        // quarentena. Antes, os oito sumiam de uma vez.
        assertEquals(1, resumo.total)
        assertEquals(1, resumo.quarentena)
        assertEquals(0, resumo.aguardandoEnvio)
    }

    @Test
    fun `o split acontece por divisao binaria, nao um a um`() {
        val ids = (1..8).map { fila.registrarInicio(item, playlist)!! }
        ids.forEach { fila.registrarFim(it) }
        api.idRuim = ids[0]

        fila.tentarEnviar()

        // 8 -> 4 -> 2 -> 1: bem menos que oito requisições individuais.
        assertTrue("esperava poucos lotes, veio ${api.lotesEnviados}", api.lotesEnviados.size <= 8)
        assertTrue(api.lotesEnviados.contains(8))
    }

    @Test
    fun `evento em quarentena nao e reenviado no ciclo seguinte`() {
        val id = fila.registrarInicio(item, playlist)!!
        fila.registrarFim(id)
        api.idRuim = id
        fila.tentarEnviar()

        val lotesDepoisDoPrimeiro = api.lotesEnviados.size
        fila.tentarEnviar()

        assertEquals(lotesDepoisDoPrimeiro, api.lotesEnviados.size)
    }

    @Test
    fun `quarentena continua visivel no diagnostico`() {
        val id = fila.registrarInicio(item, playlist)!!
        fila.registrarFim(id)
        api.idRuim = id

        fila.tentarEnviar()

        assertEquals(1, fila.resumo().quarentena)
        assertEquals(1, fila.perdas())
    }

    // ------------------------------------------------------------------- R5

    @Test
    fun `registrarFalha remove o orfao em vez de deixa-lo ocupando a fila`() {
        val id = fila.registrarInicio(item, playlist)!!
        assertEquals(1, fila.resumo().total)

        fila.registrarFalha(id)

        assertEquals(0, fila.resumo().total)
    }

    // ------------------------------------------------------------------- R6

    @Test
    fun `migracao de v1 para v2 preserva os eventos`() {
        // Simula um banco criado pela versão 1 do esquema (sem a coluna de
        // quarentena) e confere que o upgrade não apaga nada.
        contexto.deleteDatabase("teste_migracao.db")
        val antigo = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            contexto.getDatabasePath("teste_migracao.db"), null,
        )
        antigo.execSQL(
            """
            CREATE TABLE ${ProofOfPlayDb.TABELA} (
                execucao_id TEXT PRIMARY KEY, janela_id TEXT, item_programacao_id TEXT,
                criativo_id TEXT, anunciante_id TEXT, formato_legado INTEGER NOT NULL,
                iniciado_em TEXT NOT NULL, terminado_em TEXT,
                tentativas INTEGER NOT NULL DEFAULT 0,
                proximo_envio_em INTEGER NOT NULL DEFAULT 0, criado_em_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        antigo.execSQL(
            "INSERT INTO ${ProofOfPlayDb.TABELA} " +
                "(execucao_id, formato_legado, iniciado_em, criado_em_ms, terminado_em) " +
                "VALUES ('sobrevivente', 0, '2026-01-01T00:00:00Z', 1000, '2026-01-01T00:00:10Z')"
        )
        antigo.version = 1
        antigo.close()

        val migrado = object : android.database.sqlite.SQLiteOpenHelper(
            contexto, "teste_migracao.db", null, ProofOfPlayDb.VERSAO,
        ) {
            override fun onCreate(db: android.database.sqlite.SQLiteDatabase) = Unit
            override fun onUpgrade(
                db: android.database.sqlite.SQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) {
                db.execSQL("ALTER TABLE ${ProofOfPlayDb.TABELA} ADD COLUMN quarentena_motivo TEXT")
            }
        }

        migrado.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${ProofOfPlayDb.TABELA}", null,
        ).use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        migrado.close()
    }
}
