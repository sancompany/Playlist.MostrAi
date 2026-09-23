package br.com.mostrai.player.estado

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.OffsetDateTime

/**
 * Registro durável do que aconteceu com este aparelho.
 *
 * Existe por um motivo específico e não negociável: **erro em memória não
 * serve**. Se o app falha às 3h, o processo morre, o Android reinicia o
 * player e às 3h05 o heartbeat diz "tudo bem", ninguém nunca fica sabendo —
 * e "Erro do player" no admin vira um estado que nunca acende. Só o que está
 * em disco sobrevive ao caminho crash → restart → heartbeat.
 *
 * Deliberadamente pequeno: anel de [MAXIMO_EVENTOS] linhas, texto já
 * sanitizado por quem registra. Não é log de aplicação, não recebe
 * stacktrace, não recebe segredo — é a lista curta do que o operador
 * precisaria saber olhando a ficha da tela.
 */
class DiarioBordo(context: Context) {

    data class Evento(
        val codigo: String,
        val mensagem: String?,
        val emIso: String,
        val emMs: Long,
        val severidade: Severidade,
    )

    enum class Severidade { INFO, ERRO }

    private val helper = Helper(context.applicationContext)

    fun registrar(codigo: Codigo, mensagem: String? = null) {
        val agora = System.currentTimeMillis()
        val valores = ContentValues().apply {
            put("codigo", codigo.name)
            put("mensagem", mensagem?.let(::sanitizar))
            put("em_iso", OffsetDateTime.now().toString())
            put("em_ms", agora)
            put("severidade", codigo.severidade.name)
        }
        val db = helper.writableDatabase
        db.insert(TABELA, null, valores)
        podar(db)
    }

    /** O erro mais recente ainda não superado por um sinal de normalidade. */
    fun ultimoErro(): Evento? = primeiro("severidade = ?", arrayOf(Severidade.ERRO.name))

    fun ultimos(quantidade: Int): List<Evento> {
        helper.readableDatabase.query(
            TABELA, null, null, null, null, null, "em_ms DESC", quantidade.toString(),
        ).use { cursor ->
            val lista = mutableListOf<Evento>()
            while (cursor.moveToNext()) lista.add(cursor.paraEvento())
            return lista
        }
    }

    /**
     * Apaga o último erro quando o aparelho volta ao normal. Sem isto, um
     * erro transitório de rede ficaria pendurado na ficha da tela para
     * sempre, e o admin não conseguiria distinguir "quebrou agora" de
     * "quebrou uma vez semana passada".
     */
    fun limparErros() {
        helper.writableDatabase.delete(TABELA, "severidade = ?", arrayOf(Severidade.ERRO.name))
    }

    private fun primeiro(onde: String, args: Array<String>): Evento? {
        helper.readableDatabase.query(TABELA, null, onde, args, null, null, "em_ms DESC", "1").use {
            return if (it.moveToFirst()) it.paraEvento() else null
        }
    }

    private fun podar(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM $TABELA WHERE id NOT IN " +
                "(SELECT id FROM $TABELA ORDER BY em_ms DESC LIMIT $MAXIMO_EVENTOS)"
        )
    }

    private fun android.database.Cursor.paraEvento(): Evento {
        val indiceMensagem = getColumnIndexOrThrow("mensagem")
        return Evento(
            codigo = getString(getColumnIndexOrThrow("codigo")),
            mensagem = if (isNull(indiceMensagem)) null else getString(indiceMensagem),
            emIso = getString(getColumnIndexOrThrow("em_iso")),
            emMs = getLong(getColumnIndexOrThrow("em_ms")),
            severidade = Severidade.valueOf(getString(getColumnIndexOrThrow("severidade"))),
        )
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, NOME_ARQUIVO, null, VERSAO) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABELA (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    codigo TEXT NOT NULL,
                    mensagem TEXT,
                    em_iso TEXT NOT NULL,
                    em_ms INTEGER NOT NULL,
                    severidade TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_diario_em ON $TABELA(em_ms)")
        }

        // Diário é descartável por natureza (diagnóstico, não comprovante):
        // aqui recriar é aceitável, ao contrário da fila de proof-of-play.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABELA")
            onCreate(db)
        }
    }

    companion object {
        const val NOME_ARQUIVO = "mostrai_diario.db"
        const val VERSAO = 1
        const val TABELA = "evento_diario"
        const val MAXIMO_EVENTOS = 200

        /** Corta tamanho e tira o que nunca pode sair do aparelho. */
        fun sanitizar(texto: String): String = texto
            .replace(Regex("(?i)(chave|token|key|authorization|senha|password)\\s*[=:]\\s*\\S+"), "$1=***")
            .take(200)
    }

    /**
     * Vocabulário fechado de propósito: o backend precisa poder agrupar
     * ocorrências, e string livre vira lixo não agregável em três meses.
     */
    enum class Codigo(val severidade: Severidade) {
        BOOT(Severidade.INFO),
        PROVISIONADO(Severidade.INFO),
        PLAYLIST_OK(Severidade.INFO),
        PLAYLIST_FALHOU(Severidade.ERRO),
        AUTH_FALHOU(Severidade.ERRO),
        MIDIA_FALHOU(Severidade.ERRO),
        MIDIA_HASH_DIVERGENTE(Severidade.ERRO),
        PLAYBACK_FALHOU(Severidade.ERRO),
        FILA_LIMIAR(Severidade.ERRO),
        CONFIG_APLICADA(Severidade.INFO),
        CONFIG_FALHOU(Severidade.ERRO),
        UPDATE_DETECTADO(Severidade.INFO),
        UPDATE_BAIXADO(Severidade.INFO),
        UPDATE_FALHOU(Severidade.ERRO),
        UPDATE_INSTALACAO_PEDIDA(Severidade.INFO),
        CHAVE_ROTACIONADA(Severidade.INFO),
        FORA_DO_HORARIO(Severidade.INFO),
    }
}
