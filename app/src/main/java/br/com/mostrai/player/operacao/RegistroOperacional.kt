package br.com.mostrai.player.operacao

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.UUID

/**
 * Tempo operacional registrado na própria TV (Ponto Móvel, 02/10/2026).
 *
 * Heartbeat prova que a tela FALA com o servidor, não que ela FUNCIONA: uma
 * tela num evento sem internet passa dias operando sem mandar nenhum. Este
 * registro guarda o fato — "o ciclo de exibição esteve rodando de X a Y" —
 * para o servidor reconstruir depois, quando a rede voltar.
 *
 * Uma sessão = um período contínuo de ciclo ativo (provisionado, na frente,
 * reproduzindo). Ela nasce em [abrir], ganha um checkpoint a cada minuto e
 * fecha em [fechar] com o motivo. Queda de energia ou processo morto não
 * passam por [fechar]: a sessão aberta é encerrada na próxima abertura com
 * motivo `interrompida`, no último checkpoint — nunca além dele (no máximo
 * um minuto de operação real fica de fora; nunca uma hora inventada).
 *
 * **Duração pelo relógio monotônico** (`elapsedRealtime`, mesmo boot), nunca
 * pela diferença de relógio de parede: mudar a hora, o fuso ou perder a
 * sincronização não cria nem apaga tempo. Os instantes de parede e do
 * servidor vão junto só para localizar a sessão no tempo — o servidor
 * prefere o do servidor ([inicioServidorMs]) quando existe.
 *
 * O Player NÃO decide o que é tempo "válido", nem em que hospedagem ou
 * evento ele caiu: registra o fato; o servidor cruza com o local em que a
 * tela estava e com o horário do ponto.
 *
 * **Idempotente:** cada sessão tem um id gerado aqui e é reenviada inteira
 * (com o fim mais recente) até o servidor confirmar. Nada sai daqui sem
 * confirmação; só sessões confirmadas e encerradas são apagadas, depois de
 * [RETENCAO_CONFIRMADA_MS].
 */
class RegistroOperacional(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NOME_ARQUIVO, null, VERSAO) {

    private val resolver = context.applicationContext.contentResolver

    data class Sessao(
        val sessaoId: String,
        val dispositivoId: String?,
        val bootCount: Int,
        val inicioUptimeMs: Long,
        val fimUptimeMs: Long,
        val inicioParedeMs: Long,
        val fimParedeMs: Long,
        val inicioServidorMs: Long?,
        val fimServidorMs: Long?,
        val aberta: Boolean,
        val motivoFim: String?,
        /** Até onde (fim, em uptime) o servidor já confirmou; -1 = nunca. */
        val confirmadaAteUptimeMs: Long,
        val confirmadaEncerrada: Boolean,
    ) {
        val duracaoMs: Long get() = (fimUptimeMs - inicioUptimeMs).coerceAtLeast(0L)
        val pendente: Boolean get() = confirmadaAteUptimeMs < fimUptimeMs || (!aberta && !confirmadaEncerrada)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABELA (
                sessao_id TEXT PRIMARY KEY,
                dispositivo_id TEXT,
                boot_count INTEGER NOT NULL,
                inicio_uptime_ms INTEGER NOT NULL,
                fim_uptime_ms INTEGER NOT NULL,
                inicio_parede_ms INTEGER NOT NULL,
                fim_parede_ms INTEGER NOT NULL,
                inicio_servidor_ms INTEGER,
                fim_servidor_ms INTEGER,
                aberta INTEGER NOT NULL,
                motivo_fim TEXT,
                confirmada_ate_uptime_ms INTEGER NOT NULL DEFAULT -1,
                confirmada_encerrada INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /**
     * Abre uma sessão nova e encerra qualquer outra que tenha ficado aberta
     * (processo morto, queda de energia) no último checkpoint dela.
     */
    @Synchronized
    fun abrir(dispositivoId: String?, servidorMs: Long?): String? = seguro(null) {
        encerrarInterrompidas()
        val id = UUID.randomUUID().toString()
        val uptime = SystemClock.elapsedRealtime()
        val parede = System.currentTimeMillis()
        writableDatabase.insertOrThrow(
            TABELA, null,
            ContentValues().apply {
                put("sessao_id", id)
                put("dispositivo_id", dispositivoId)
                put("boot_count", contagemDeBoot())
                put("inicio_uptime_ms", uptime)
                put("fim_uptime_ms", uptime)
                put("inicio_parede_ms", parede)
                put("fim_parede_ms", parede)
                if (servidorMs != null) {
                    put("inicio_servidor_ms", servidorMs)
                    put("fim_servidor_ms", servidorMs)
                }
                put("aberta", 1)
            },
        )
        id
    }

    /** Estende a sessão até agora. Chamado a cada minuto enquanto o ciclo roda. */
    @Synchronized
    fun checkpoint(sessaoId: String, servidorMs: Long?) {
        seguro(Unit) { estender(sessaoId, servidorMs, fechar = null) }
    }

    @Synchronized
    fun fechar(sessaoId: String, motivo: String, servidorMs: Long?) {
        seguro(Unit) { estender(sessaoId, servidorMs, fechar = motivo) }
    }

    /** Sessões que o servidor ainda não confirmou por inteiro, mais antigas primeiro. */
    @Synchronized
    fun pendentes(limite: Int = LIMITE_LOTE): List<Sessao> = seguro(emptyList()) {
        readableDatabase.query(
            TABELA, null,
            "confirmada_ate_uptime_ms < fim_uptime_ms OR (aberta = 0 AND confirmada_encerrada = 0)",
            null, null, null, "inicio_parede_ms ASC", limite.toString(),
        ).use { c -> generateSequence { if (c.moveToNext()) c.paraSessao() else null }.toList() }
    }

    /** Resumo para o bloco técnico: quantas sessões e quanto tempo ainda não confirmados. */
    @Synchronized
    fun resumoPendente(): Pair<Int, Long> = seguro(0 to 0L) {
        val todas = pendentes(Int.MAX_VALUE)
        todas.size to todas.sumOf { it.duracaoMs }
    }

    /**
     * O servidor confirmou estas sessões até o fim que foi enviado. Se a
     * sessão andou depois do envio, o resto fica pendente para o próximo.
     */
    @Synchronized
    fun confirmar(enviadas: List<Sessao>, confirmadas: Set<String>) {
        seguro(Unit) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (s in enviadas) {
                    if (s.sessaoId !in confirmadas) continue
                    db.update(
                        TABELA,
                        ContentValues().apply {
                            put("confirmada_ate_uptime_ms", s.fimUptimeMs)
                            if (!s.aberta) put("confirmada_encerrada", 1)
                        },
                        "sessao_id = ? AND confirmada_ate_uptime_ms < ?",
                        arrayOf(s.sessaoId, s.fimUptimeMs.toString()),
                    )
                    if (!s.aberta) {
                        db.update(
                            TABELA, ContentValues().apply { put("confirmada_encerrada", 1) },
                            "sessao_id = ?", arrayOf(s.sessaoId),
                        )
                    }
                }
                // Encerrada e confirmada há tempo: o servidor já tem a cópia.
                db.delete(
                    TABELA,
                    "aberta = 0 AND confirmada_encerrada = 1 AND confirmada_ate_uptime_ms >= fim_uptime_ms AND fim_parede_ms < ?",
                    arrayOf((System.currentTimeMillis() - RETENCAO_CONFIRMADA_MS).toString()),
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Estende a sessão até agora. O relógio do servidor anda com o
     * monotônico dentro do mesmo boot, então: com o instante do servidor em
     * mãos, um início que ficou sem ele (sessão aberta antes da primeira
     * âncora) é recalculado para trás; sem ele (âncora perdida no meio do
     * ciclo), o fim de servidor anterior é projetado para a frente — nunca
     * fica parado enquanto a duração cresce.
     */
    private fun estender(sessaoId: String, servidorMs: Long?, fechar: String?) {
        val agoraUptime = SystemClock.elapsedRealtime()
        // Só a sessão aberta e do mesmo boot anda: depois de um reboot o
        // uptime recomeça do zero e "estender" criaria duração negativa.
        // (No UPDATE do SQLite, toda expressão do SET lê os valores ANTIGOS
        // da linha.)
        writableDatabase.execSQL(
            """
            UPDATE $TABELA SET
              inicio_servidor_ms = CASE
                WHEN inicio_servidor_ms IS NULL AND ?1 IS NOT NULL THEN ?1 - (?2 - inicio_uptime_ms)
                ELSE inicio_servidor_ms END,
              fim_servidor_ms = CASE
                WHEN ?1 IS NOT NULL THEN ?1
                WHEN fim_servidor_ms IS NOT NULL THEN fim_servidor_ms + (?2 - fim_uptime_ms)
                ELSE NULL END,
              fim_uptime_ms = ?2,
              fim_parede_ms = ?3,
              aberta = CASE WHEN ?4 IS NULL THEN aberta ELSE 0 END,
              motivo_fim = COALESCE(?4, motivo_fim)
            WHERE sessao_id = ?5 AND aberta = 1 AND boot_count = ?6
            """.trimIndent(),
            arrayOf<Any?>(servidorMs, agoraUptime, System.currentTimeMillis(), fechar, sessaoId, contagemDeBoot()),
        )
    }

    private fun encerrarInterrompidas() {
        writableDatabase.update(
            TABELA,
            ContentValues().apply {
                put("aberta", 0)
                put("motivo_fim", MOTIVO_INTERROMPIDA)
            },
            "aberta = 1", null,
        )
    }

    private fun contagemDeBoot(): Int =
        runCatching { Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    private fun Cursor.paraSessao() = Sessao(
        sessaoId = getString(getColumnIndexOrThrow("sessao_id")),
        dispositivoId = getString(getColumnIndexOrThrow("dispositivo_id")),
        bootCount = getInt(getColumnIndexOrThrow("boot_count")),
        inicioUptimeMs = getLong(getColumnIndexOrThrow("inicio_uptime_ms")),
        fimUptimeMs = getLong(getColumnIndexOrThrow("fim_uptime_ms")),
        inicioParedeMs = getLong(getColumnIndexOrThrow("inicio_parede_ms")),
        fimParedeMs = getLong(getColumnIndexOrThrow("fim_parede_ms")),
        inicioServidorMs = longOuNulo("inicio_servidor_ms"),
        fimServidorMs = longOuNulo("fim_servidor_ms"),
        aberta = getInt(getColumnIndexOrThrow("aberta")) == 1,
        motivoFim = getString(getColumnIndexOrThrow("motivo_fim")),
        confirmadaAteUptimeMs = getLong(getColumnIndexOrThrow("confirmada_ate_uptime_ms")),
        confirmadaEncerrada = getInt(getColumnIndexOrThrow("confirmada_encerrada")) == 1,
    )

    private fun Cursor.longOuNulo(coluna: String): Long? {
        val i = getColumnIndexOrThrow(coluna)
        return if (isNull(i)) null else getLong(i)
    }

    /** Disco cheio ou banco ilegível não derrubam a tela: o registro só deixa de crescer. */
    private inline fun <T> seguro(padrao: T, bloco: () -> T): T = try {
        bloco()
    } catch (e: SQLiteException) {
        Log.e(TAG, "registro operacional indisponível: ${e.javaClass.simpleName}")
        padrao
    }

    companion object {
        private const val TAG = "RegistroOperacional"
        const val NOME_ARQUIVO = "mostrai_operacao.db"
        const val VERSAO = 1
        const val TABELA = "sessao_operacional"
        const val LIMITE_LOTE = 50
        const val MOTIVO_INTERROMPIDA = "interrompida"
        const val RETENCAO_CONFIRMADA_MS = 7L * 24 * 60 * 60 * 1000
    }
}
