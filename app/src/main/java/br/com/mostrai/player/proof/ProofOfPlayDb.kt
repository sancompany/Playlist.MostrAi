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
 *
 * As colunas `anunciante_id` e `formato_legado` são do contrato antigo e
 * não são mais lidas; ficam na tabela porque removê-las exigiria recriar a
 * tabela da única cópia do comprovante — `formato_legado` segue gravado 0
 * (é `NOT NULL`).
 *
 * **Migração é incremental, nunca destrutiva** (R6): a fila é a única cópia
 * do comprovante de exibição de um anunciante até o servidor confirmar. Um
 * `DROP TABLE` num bump de esquema apagaria receita já entregue, então
 * [onUpgrade] aplica um passo por versão e [onDowngrade] prefere manter
 * dados que não entende a recriar a tabela.
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
                criado_em_ms INTEGER NOT NULL,
                quarentena_motivo TEXT
            )
            """.trimIndent()
        )
        criarIndices(db)
    }

    /**
     * Um passo por versão, sem `DROP`. Cada bloco precisa ser idempotente o
     * bastante para sobreviver a um upgrade interrompido no meio (a TV pode
     * perder energia a qualquer momento).
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            if (!temColuna(db, "quarentena_motivo")) {
                db.execSQL("ALTER TABLE $TABELA ADD COLUMN quarentena_motivo TEXT")
            }
        }
        criarIndices(db)
    }

    /**
     * Banco de uma versão futura numa instalação mais antiga (downgrade do
     * APK). Apagar seria pior que conviver: as colunas que este código
     * conhece continuam lá, e as que ele não conhece são simplesmente
     * ignoradas pelas consultas nomeadas.
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun criarIndices(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_elegiveis ON $TABELA" +
                "(terminado_em, quarentena_motivo, proximo_envio_em)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_idade ON $TABELA(criado_em_ms)")
    }

    private fun temColuna(db: SQLiteDatabase, coluna: String): Boolean {
        db.rawQuery("PRAGMA table_info($TABELA)", null).use { cursor ->
            val indiceNome = cursor.getColumnIndex("name")
            if (indiceNome < 0) return false
            while (cursor.moveToNext()) {
                if (cursor.getString(indiceNome) == coluna) return true
            }
        }
        return false
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

    /**
     * Tira o evento da fila de envio sem apagá-lo: o servidor rejeitou o
     * payload e nenhuma retentativa vai mudar isso, mas jogar fora em
     * silêncio esconderia a falha justamente de quem precisa investigá-la
     * (R4). A linha some sozinha pelo horizonte de [removerExpirados].
     */
    fun marcarQuarentena(execucaoId: String, motivo: String) {
        val valores = ContentValues().apply { put("quarentena_motivo", motivo) }
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
            "terminado_em IS NOT NULL AND quarentena_motivo IS NULL AND proximo_envio_em <= ?",
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

    fun contarPendentes(): Int = contar(null)

    /** Só o que ainda pode virar comprovante — é o número que interessa ao admin. */
    fun contarAguardandoEnvio(): Int = contar(AGUARDANDO_ENVIO)

    fun contarQuarentena(): Int = contar("quarentena_motivo IS NOT NULL")

    private fun contar(onde: String?): Int {
        val sql = if (onde == null) "SELECT COUNT(*) FROM $TABELA" else "SELECT COUNT(*) FROM $TABELA WHERE $onde"
        readableDatabase.rawQuery(sql, null).use {
            it.moveToFirst()
            return it.getInt(0)
        }
    }

    /** `criado_em_ms` do evento mais antigo que ainda espera envio, ou null. */
    fun maisAntigoAguardandoEnvioMs(): Long? {
        readableDatabase.query(
            TABELA, arrayOf("criado_em_ms"),
            "terminado_em IS NOT NULL AND quarentena_motivo IS NULL",
            null, null, null, "criado_em_ms ASC", "1",
        ).use {
            return if (it.moveToFirst()) it.getLong(0) else null
        }
    }

    /**
     * Quem sai primeiro quando a fila enche (R2). A ordem é de valor
     * crescente, e não pode ser invertida: comprovante terminado é receita
     * que o anunciante já consumiu e que só existe aqui até o servidor
     * confirmar, então é o último a morrer.
     *
     * 1. Órfão — exibição que começou e nunca terminou (queda de energia,
     *    reposicionamento). Nunca vai ser enviada; ocupa lugar à toa.
     * 2. Quarentena — o servidor já rejeitou; fica só para diagnóstico.
     * 3. Terminado — comprovante legítimo aguardando envio.
     */
    fun proximoADescartar(): String? {
        val ordens = listOf(
            "terminado_em IS NULL",
            "quarentena_motivo IS NOT NULL",
            null,
        )
        for (onde in ordens) {
            readableDatabase.query(
                TABELA, arrayOf("execucao_id"), onde, null, null, null, "criado_em_ms ASC", "1",
            ).use {
                if (it.moveToFirst()) return it.getString(0)
            }
        }
        return null
    }

    /**
     * Quantos comprovantes de verdade — terminados e fora da quarentena —
     * existem antes de [limiteMs]. Órfão nunca foi comprovante, e quarentena
     * já foi contada como perda ao entrar nela (BUG-017).
     */
    fun contarComprovantesAntesDe(limiteMs: Long): Int =
        contar("criado_em_ms < $limiteMs AND $AGUARDANDO_ENVIO")

    fun aguardaEnvio(execucaoId: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM $TABELA WHERE execucao_id = ? AND $AGUARDANDO_ENVIO", arrayOf(execucaoId),
        ).use { return it.moveToFirst() }
    }

    /** Horizonte local ([FilaProofOfPlay.HORIZONTE_EXPIRACAO_MS]) — contabilidade, não decisão de crédito. */
    fun removerExpirados(limiteMs: Long): Int =
        writableDatabase.delete(TABELA, "criado_em_ms < ?", arrayOf(limiteMs.toString()))

    private fun EventoExibicao.paraValores(): ContentValues = ContentValues().apply {
        put("execucao_id", execucaoId)
        put("janela_id", janelaId)
        put("item_programacao_id", itemProgramacaoId)
        put("criativo_id", criativoId)
        putNull("anunciante_id")
        put("formato_legado", 0)
        put("iniciado_em", iniciadoEm)
        put("terminado_em", terminadoEm)
        put("tentativas", tentativas)
        put("proximo_envio_em", proximoEnvioElegivelEm)
        put("criado_em_ms", criadoEmMs)
        put("quarentena_motivo", quarentenaMotivo)
    }

    private fun Cursor.paraEvento(): EventoExibicao = EventoExibicao(
        execucaoId = getString(getColumnIndexOrThrow("execucao_id")),
        janelaId = stringOuNulo("janela_id"),
        itemProgramacaoId = stringOuNulo("item_programacao_id"),
        criativoId = stringOuNulo("criativo_id"),
        iniciadoEm = getString(getColumnIndexOrThrow("iniciado_em")),
        terminadoEm = stringOuNulo("terminado_em"),
        tentativas = getInt(getColumnIndexOrThrow("tentativas")),
        proximoEnvioElegivelEm = getLong(getColumnIndexOrThrow("proximo_envio_em")),
        criadoEmMs = getLong(getColumnIndexOrThrow("criado_em_ms")),
        quarentenaMotivo = stringOuNulo("quarentena_motivo"),
    )

    private fun Cursor.stringOuNulo(coluna: String): String? {
        val i = getColumnIndexOrThrow(coluna)
        return if (isNull(i)) null else getString(i)
    }

    companion object {
        const val NOME_ARQUIVO = "mostrai_proof_of_play.db"

        /** 1 → 2: coluna `quarentena_motivo` (R4). */
        const val VERSAO = 2
        const val TABELA = "evento_exibicao"

        private const val AGUARDANDO_ENVIO = "terminado_em IS NOT NULL AND quarentena_motivo IS NULL"

        /** Compartilhado com [FilaProofOfPlay] para o contador de perda. */
        const val PREFS_PERDAS = "mostrai_perdas"
        const val CHAVE_PERDAS = "eventos_perdidos"
    }
}
