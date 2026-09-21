package br.com.mostrai.player.proof

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Fila durável de proof-of-play em SQLite (decisão 6.4: sobrevive a reboot e
 * a queda de energia).
 *
 * Sem dependência externa (Room) de propósito — o esquema é pequeno e as
 * consultas são simples; `SQLiteOpenHelper` já entrega a durabilidade que a
 * decisão exige sem esticar a árvore de dependências do projeto.
 */
class ProofOfPlayDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NOME_ARQUIVO, null, VERSAO) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABELA (
                execucao_id TEXT PRIMARY KEY,
                janela_id TEXT,
                item_programacao_id TEXT,
                criativo_id TEXT,
                anunciante_id TEXT,
                formato_legado INTEGER NOT NULL,
                iniciado_em TEXT NOT NULL,
                terminado_em TEXT,
                tentativas INTEGER NOT NULL DEFAULT 0,
                proximo_envio_em INTEGER NOT NULL DEFAULT 0,
                criado_em_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_elegiveis ON $TABELA(terminado_em, proximo_envio_em)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABELA")
        onCreate(db)
    }

    fun inserir(evento: EventoExibicao) {
        writableDatabase.insertWithOnConflict(TABELA, null, evento.paraValores(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun marcarTerminado(execucaoId: String, terminadoEm: String) {
        val valores = ContentValues().apply {
            put("terminado_em", terminadoEm)
            put("proximo_envio_em", 0L)
        }
        writableDatabase.update(TABELA, valores, "execucao_id = ?", arrayOf(execucaoId))
    }

    fun remover(execucaoId: String) {
        writableDatabase.delete(TABELA, "execucao_id = ?", arrayOf(execucaoId))
    }

    fun removerLote(execucaoIds: Collection<String>) {
        if (execucaoIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            execucaoIds.forEach { db.delete(TABELA, "execucao_id = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun elegiveisParaEnvio(agoraMs: Long, limite: Int): List<EventoExibicao> {
        readableDatabase.query(
            TABELA, null,
            "terminado_em IS NOT NULL AND proximo_envio_em <= ?",
            arrayOf(agoraMs.toString()),
            null, null,
            "criado_em_ms ASC",
            limite.toString(),
        ).use { cursor ->
            val lista = mutableListOf<EventoExibicao>()
            while (cursor.moveToNext()) lista.add(cursor.paraEvento())
            return lista
        }
    }

    fun adiarReenvio(execucaoId: String, proximoEnvioMs: Long, tentativas: Int) {
        val valores = ContentValues().apply {
            put("proximo_envio_em", proximoEnvioMs)
            put("tentativas", tentativas)
        }
        writableDatabase.update(TABELA, valores, "execucao_id = ?", arrayOf(execucaoId))
    }

    fun contarPendentes(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABELA", null).use {
            it.moveToFirst()
            return it.getInt(0)
        }
    }

    fun maisAntigoNaoEnviado(): String? {
        readableDatabase.query(
            TABELA, arrayOf("execucao_id"), null, null, null, null, "criado_em_ms ASC", "1",
        ).use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    /** Horizonte local de 7 dias (seção 6.5) — contabilidade, não decisão de crédito. */
    fun removerExpirados(limiteMs: Long): Int =
        writableDatabase.delete(TABELA, "criado_em_ms < ?", arrayOf(limiteMs.toString()))

    private fun EventoExibicao.paraValores(): ContentValues = ContentValues().apply {
        put("execucao_id", execucaoId)
        put("janela_id", janelaId)
        put("item_programacao_id", itemProgramacaoId)
        put("criativo_id", criativoId)
        put("anunciante_id", anuncianteId)
        put("formato_legado", if (formatoLegado) 1 else 0)
        put("iniciado_em", iniciadoEm)
        put("terminado_em", terminadoEm)
        put("tentativas", tentativas)
        put("proximo_envio_em", proximoEnvioElegivelEm)
        put("criado_em_ms", criadoEmMs)
    }

    private fun Cursor.paraEvento(): EventoExibicao = EventoExibicao(
        execucaoId = getString(getColumnIndexOrThrow("execucao_id")),
        janelaId = stringOuNulo("janela_id"),
        itemProgramacaoId = stringOuNulo("item_programacao_id"),
        criativoId = stringOuNulo("criativo_id"),
        anuncianteId = stringOuNulo("anunciante_id"),
        formatoLegado = getInt(getColumnIndexOrThrow("formato_legado")) == 1,
        iniciadoEm = getString(getColumnIndexOrThrow("iniciado_em")),
        terminadoEm = stringOuNulo("terminado_em"),
        tentativas = getInt(getColumnIndexOrThrow("tentativas")),
        proximoEnvioElegivelEm = getLong(getColumnIndexOrThrow("proximo_envio_em")),
        criadoEmMs = getLong(getColumnIndexOrThrow("criado_em_ms")),
    )

    private fun Cursor.stringOuNulo(coluna: String): String? {
        val i = getColumnIndexOrThrow(coluna)
        return if (isNull(i)) null else getString(i)
    }

    companion object {
        const val NOME_ARQUIVO = "mostrai_proof_of_play.db"
        const val VERSAO = 1
        const val TABELA = "evento_exibicao"

        /** Compartilhado com [FilaProofOfPlay] para o contador de perda. */
        const val PREFS_PERDAS = "mostrai_perdas"
        const val CHAVE_PERDAS = "eventos_perdidos"
    }
}
