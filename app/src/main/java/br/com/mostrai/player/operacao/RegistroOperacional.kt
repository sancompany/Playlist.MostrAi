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
import java.security.SecureRandom

/**
 * Tempo operacional medido na própria TV (Ponto Móvel) — contrato
 * `sancompany/MostrAi` `docs/player-mvp-contract.md` §8.5.
 *
 * Heartbeat prova que a tela FALA com o servidor, não que ela FUNCIONA: uma
 * tela num evento sem internet passa dias exibindo sem mandar nenhum. Este
 * registro guarda o fato — "de X a Y a tela esteve exibindo" — em
 * **segmentos** `{bootId, seq, inicio, fim}`, e manda quando a rede volta.
 *
 * Regras do contrato:
 * - **Só exibindo:** o segmento abre quando a tela entra em `PLAYING`/`IDLE`
 *   com a Activity na frente, e fecha quando sai disso (fora do horário,
 *   erro, saída, standby). Quem decide é [PlayerActivity]; aqui só o fato.
 * - **Relógio monotônico:** início e fim em `elapsedRealtime` do boot, nunca
 *   no relógio de parede — acertar a hora, o fuso ou o NTP não cria nem apaga
 *   tempo. Para mandar, cada boot precisa de uma **âncora** do servidor
 *   (`servidorAgora` de uma playlist recebida NESSE boot): `inicio` e `fim`
 *   saem em hora do servidor = uptime + deslocamento da âncora.
 * - **Boot sem âncora é descartado** — a TV ligou sem internet e desligou sem
 *   nunca ter falado com o servidor: não há como pôr esse tempo no relógio
 *   do servidor sem confiar no relógio da TV. O contrato manda descartar;
 *   o descarte fica no diário ([Descarte]).
 * - **Até 6 h por segmento:** passou disso, fecha e abre o próximo `seq`.
 * - **Idempotente:** `(bootId, seq)` é a chave; o segmento aberto é
 *   reenviado maior e o servidor só estende. Sai daqui só com resposta final
 *   (`ok` do segmento fechado, `item_invalido`, `ignorado`).
 *
 * O Player NÃO decide o que é tempo válido, nem a que hospedagem ou evento
 * ele pertence: o servidor cruza com o ponto da tela e o estado do cadastro.
 */
class RegistroOperacional(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NOME_ARQUIVO, null, VERSAO) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(ARQUIVO_BOOT, Context.MODE_PRIVATE)

    init {
        // O formato de sessões (PR #7, nunca publicado) não vale mais.
        app.deleteDatabase(NOME_ARQUIVO_ANTIGO)
    }

    data class Segmento(
        val bootId: String,
        val seq: Int,
        val dispositivoId: String?,
        val inicioUptimeMs: Long,
        val fimUptimeMs: Long,
        val aberto: Boolean,
        /** Fim (uptime) que o servidor já confirmou; -1 = nunca. */
        val confirmadoAteUptimeMs: Long,
        /** Deslocamento da âncora deste boot (servidor − uptime), ou null. */
        val deslocamentoMs: Long?,
    ) {
        val duracaoMs: Long get() = (fimUptimeMs - inicioUptimeMs).coerceAtLeast(0L)
        val inicioServidorMs: Long? get() = deslocamentoMs?.let { inicioUptimeMs + it }
        val fimServidorMs: Long? get() = deslocamentoMs?.let { fimUptimeMs + it }
    }

    /** Tempo que nunca vai ao servidor: boot que terminou sem âncora. */
    data class Descarte(val segmentos: Int, val duracaoMs: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABELA (
                boot_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                dispositivo_id TEXT,
                inicio_uptime_ms INTEGER NOT NULL,
                fim_uptime_ms INTEGER NOT NULL,
                aberto INTEGER NOT NULL,
                confirmado_ate_uptime_ms INTEGER NOT NULL DEFAULT -1,
                PRIMARY KEY (boot_id, seq)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE TABLE $TABELA_ANCORA (boot_id TEXT PRIMARY KEY, deslocamento_ms INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ------------------------------------------------------------------- boot

    /**
     * Identidade do boot atual: `b<BOOT_COUNT>.<aleatório>`. Mesmo boot =
     * mesmo `BOOT_COUNT` (ou, se o sistema não o expõe, uptime que só cresceu
     * desde o último registro). Um processo novo no mesmo boot reaproveita o
     * id — os uptimes continuam comparáveis.
     */
    @Synchronized
    fun bootAtual(): String {
        val contagem = contagemDeBoot()
        val uptime = SystemClock.elapsedRealtime()
        val guardado = prefs.getString(CHAVE_BOOT_ID, null)
        val contagemGuardada = prefs.getInt(CHAVE_BOOT_COUNT, Int.MIN_VALUE)
        val uptimeGuardado = prefs.getLong(CHAVE_ULTIMO_UPTIME, Long.MAX_VALUE)
        // Uptime que caiu prova reboot, mesmo com BOOT_COUNT igual (firmware
        // que não o incrementa).
        val mesmoBoot = guardado != null && contagemGuardada == contagem && uptime >= uptimeGuardado
        val id = if (mesmoBoot) guardado!! else "b${if (contagem >= 0) contagem else "x"}.${aleatorio()}"
        val editor = prefs.edit().putLong(CHAVE_ULTIMO_UPTIME, uptime)
        if (!mesmoBoot) editor.putString(CHAVE_BOOT_ID, id).putInt(CHAVE_BOOT_COUNT, contagem)
        editor.commit()
        return id
    }

    /**
     * Primeiro contato com o servidor neste boot: fixa o deslocamento entre o
     * relógio do servidor e o uptime. Fica o primeiro — reenvios do segmento
     * aberto nunca mudam de lugar no tempo.
     */
    @Synchronized
    fun ancorar(servidorAgoraMs: Long, uptimeMs: Long = SystemClock.elapsedRealtime()) {
        seguro(Unit) {
            val boot = bootAtual()
            writableDatabase.insertWithOnConflict(
                TABELA_ANCORA, null,
                ContentValues().apply {
                    put("boot_id", boot)
                    put("deslocamento_ms", servidorAgoraMs - uptimeMs)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    // ------------------------------------------------------------- segmentos

    /** A tela começou a exibir: abre um segmento se ainda não há um aberto neste boot. */
    @Synchronized
    fun abrir(dispositivoId: String?) {
        seguro(Unit) {
            val boot = bootAtual()
            if (aberto(boot) != null) return@seguro
            val agora = SystemClock.elapsedRealtime()
            inserir(boot, proximoSeq(boot), dispositivoId, agora, agora)
        }
    }

    /**
     * Estende o segmento aberto até agora (checkpoint a cada 30 s). Passou de
     * [SEGMENTO_MAXIMO_MS], fecha naquele limite e continua num `seq` novo.
     */
    @Synchronized
    fun estender() {
        seguro(Unit) {
            val boot = bootAtual()
            var atual = aberto(boot) ?: return@seguro
            val agora = SystemClock.elapsedRealtime()
            while (agora - atual.inicioUptimeMs > SEGMENTO_MAXIMO_MS) {
                val limite = atual.inicioUptimeMs + SEGMENTO_MAXIMO_MS
                atualizarFim(boot, atual.seq, limite, aberto = false)
                val seq = proximoSeq(boot)
                inserir(boot, seq, atual.dispositivoId, limite, limite)
                atual = aberto(boot) ?: return@seguro
            }
            atualizarFim(boot, atual.seq, agora, aberto = true)
        }
    }

    /** A tela parou de exibir: fecha o segmento aberto agora. */
    @Synchronized
    fun fechar() {
        seguro(Unit) {
            estender()
            val boot = bootAtual()
            val atual = aberto(boot) ?: return@seguro
            atualizarFim(boot, atual.seq, atual.fimUptimeMs, aberto = false)
        }
    }

    /**
     * Começo de processo: o que ficou aberto (processo morto, queda de
     * energia) fecha no último checkpoint — nunca além dele. Segmentos de um
     * boot anterior que nunca teve âncora são descartados (contrato §8.5), e
     * os de outra tela (reinstalação como outra tela) também: não há com
     * que credencial mandá-los.
     */
    @Synchronized
    fun arrumar(dispositivoAtual: String?): Descarte = seguro(Descarte(0, 0)) {
        val boot = bootAtual()
        writableDatabase.execSQL("UPDATE $TABELA SET aberto = 0 WHERE aberto = 1")
        val semAncora = "boot_id <> ? AND boot_id NOT IN (SELECT boot_id FROM $TABELA_ANCORA)"
        val descartados = todos("$semAncora AND fim_uptime_ms > inicio_uptime_ms", arrayOf(boot))
        writableDatabase.delete(TABELA, semAncora, arrayOf(boot))
        if (dispositivoAtual != null) {
            writableDatabase.delete(TABELA, "dispositivo_id IS NOT ?", arrayOf(dispositivoAtual))
        }
        // Fechado e já confirmado até o fim (o processo morreu entre o `ok`
        // do aberto e o próximo checkpoint): não tem mais o que mandar.
        writableDatabase.delete(TABELA, "aberto = 0 AND confirmado_ate_uptime_ms >= fim_uptime_ms", null)
        writableDatabase.delete(TABELA_ANCORA, "boot_id <> ? AND boot_id NOT IN (SELECT boot_id FROM $TABELA)", arrayOf(boot))
        // Contador de seq só importa no boot atual.
        prefs.all.keys.filter { it.startsWith(CHAVE_PROXIMO_SEQ_PREFIXO) && it != CHAVE_PROXIMO_SEQ_PREFIXO + boot }
            .takeIf { it.isNotEmpty() }
            ?.let { velhas -> prefs.edit().apply { velhas.forEach(::remove) }.commit() }
        Descarte(descartados.size, descartados.sumOf { it.duracaoMs })
    }

    /**
     * O que ainda precisa ir ao servidor, mais antigo primeiro: só segmentos
     * com âncora (os do boot atual sem âncora esperam) e com algo novo — o
     * aberto cresceu, ou o fechado ainda não teve resposta final.
     */
    @Synchronized
    fun pendentes(dispositivoId: String?, limite: Int = TETO_LOTE): List<Segmento> = seguro(emptyList()) {
        if (dispositivoId == null) return@seguro emptyList()
        todos(
            "dispositivo_id = ? AND fim_uptime_ms > inicio_uptime_ms AND fim_uptime_ms > confirmado_ate_uptime_ms " +
                "AND deslocamento_ms IS NOT NULL",
            arrayOf(dispositivoId),
            limite,
        )
    }

    /**
     * Resposta do servidor por `(bootId, seq)`: `ok` confirma até o fim
     * enviado (o fechado sai daqui; o aberto volta quando crescer);
     * `item_invalido` e `ignorado` são finais — sai daqui, nunca mais vai.
     */
    @Synchronized
    fun confirmar(enviados: List<Segmento>, resultados: Map<Pair<String, Int>, String>) {
        seguro(Unit) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (s in enviados) {
                    when (resultados[s.bootId to s.seq]) {
                        STATUS_OK -> {
                            db.execSQL(
                                "UPDATE $TABELA SET confirmado_ate_uptime_ms = MAX(confirmado_ate_uptime_ms, ?) " +
                                    "WHERE boot_id = ? AND seq = ?",
                                arrayOf<Any>(s.fimUptimeMs, s.bootId, s.seq),
                            )
                            db.delete(
                                TABELA, "boot_id = ? AND seq = ? AND aberto = 0 AND confirmado_ate_uptime_ms >= fim_uptime_ms",
                                arrayOf(s.bootId, s.seq.toString()),
                            )
                        }
                        STATUS_INVALIDO, STATUS_IGNORADO -> db.delete(
                            TABELA, "boot_id = ? AND seq = ? AND aberto = 0",
                            arrayOf(s.bootId, s.seq.toString()),
                        ).also {
                            // Aberto e já recusado: não volta a ir até crescer.
                            db.execSQL(
                                "UPDATE $TABELA SET confirmado_ate_uptime_ms = ? WHERE boot_id = ? AND seq = ?",
                                arrayOf<Any>(s.fimUptimeMs, s.bootId, s.seq),
                            )
                        }
                        else -> Unit // sem resultado: fica para a próxima
                    }
                }
                // Fechado de duração zero não tem o que provar.
                db.delete(TABELA, "aberto = 0 AND fim_uptime_ms <= inicio_uptime_ms", null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Para o bloco técnico: segmentos ainda não confirmados e o tempo neles. */
    @Synchronized
    fun resumoPendente(): Pair<Int, Long> = seguro(0 to 0L) {
        val todos = todos("fim_uptime_ms > confirmado_ate_uptime_ms", emptyArray(), Int.MAX_VALUE)
        todos.size to todos.sumOf { it.duracaoMs }
    }

    /** Há âncora do servidor neste boot? (bloco técnico e testes) */
    @Synchronized
    fun ancorado(): Boolean = seguro(false) {
        readableDatabase.rawQuery("SELECT 1 FROM $TABELA_ANCORA WHERE boot_id = ?", arrayOf(bootAtual())).use { it.moveToFirst() }
    }

    // ---------------------------------------------------------------- interno

    private fun aberto(boot: String): Segmento? =
        todos("boot_id = ? AND aberto = 1", arrayOf(boot), 1).firstOrNull()

    private fun proximoSeq(boot: String): Int =
        readableDatabase.rawQuery("SELECT COALESCE(MAX(seq), -1) + 1 FROM $TABELA WHERE boot_id = ?", arrayOf(boot))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            .coerceAtLeast(prefs.getInt(CHAVE_PROXIMO_SEQ_PREFIXO + boot, 0))
            .also { prefs.edit().putInt(CHAVE_PROXIMO_SEQ_PREFIXO + boot, it + 1).commit() }

    private fun inserir(boot: String, seq: Int, dispositivoId: String?, inicio: Long, fim: Long) {
        writableDatabase.insertOrThrow(
            TABELA, null,
            ContentValues().apply {
                put("boot_id", boot)
                put("seq", seq)
                put("dispositivo_id", dispositivoId)
                put("inicio_uptime_ms", inicio)
                put("fim_uptime_ms", fim)
                put("aberto", 1)
            },
        )
    }

    private fun atualizarFim(boot: String, seq: Int, fim: Long, aberto: Boolean) {
        writableDatabase.execSQL(
            "UPDATE $TABELA SET fim_uptime_ms = MAX(fim_uptime_ms, ?), aberto = ? WHERE boot_id = ? AND seq = ?",
            arrayOf<Any>(fim, if (aberto) 1 else 0, boot, seq),
        )
    }

    private fun todos(onde: String, args: Array<String>, limite: Int = Int.MAX_VALUE): List<Segmento> =
        readableDatabase.rawQuery(
            "SELECT * FROM (SELECT s.*, a.deslocamento_ms FROM $TABELA s " +
                "LEFT JOIN $TABELA_ANCORA a ON a.boot_id = s.boot_id) " +
                "WHERE $onde ORDER BY boot_id, seq LIMIT $limite",
            args,
        ).use { c -> generateSequence { if (c.moveToNext()) c.paraSegmento() else null }.toList() }

    private fun Cursor.paraSegmento() = Segmento(
        bootId = getString(getColumnIndexOrThrow("boot_id")),
        seq = getInt(getColumnIndexOrThrow("seq")),
        dispositivoId = getColumnIndexOrThrow("dispositivo_id").let { if (isNull(it)) null else getString(it) },
        inicioUptimeMs = getLong(getColumnIndexOrThrow("inicio_uptime_ms")),
        fimUptimeMs = getLong(getColumnIndexOrThrow("fim_uptime_ms")),
        aberto = getInt(getColumnIndexOrThrow("aberto")) == 1,
        confirmadoAteUptimeMs = getLong(getColumnIndexOrThrow("confirmado_ate_uptime_ms")),
        deslocamentoMs = getColumnIndexOrThrow("deslocamento_ms").let { if (isNull(it)) null else getLong(it) },
    )

    private fun contagemDeBoot(): Int =
        runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    private fun aleatorio(): String {
        val alfabeto = "abcdefghijklmnopqrstuvwxyz0123456789"
        val r = SecureRandom()
        return String(CharArray(8) { alfabeto[r.nextInt(alfabeto.length)] })
    }

    /** Disco cheio ou banco corrompido nunca derrubam o Player — só este registro falha. */
    private inline fun <T> seguro(padrao: T, bloco: () -> T): T = try {
        bloco()
    } catch (e: SQLiteException) {
        Log.w(TAG, "registro operacional indisponível", e)
        padrao
    } catch (e: IllegalStateException) {
        Log.w(TAG, "registro operacional indisponível", e)
        padrao
    }

    companion object {
        private const val TAG = "RegistroOperacional"
        const val NOME_ARQUIVO = "mostrai_operacao_v2.db"
        const val NOME_ARQUIVO_ANTIGO = "mostrai_operacao.db"
        private const val VERSAO = 1
        private const val TABELA = "segmento"
        private const val TABELA_ANCORA = "ancora"
        const val ARQUIVO_BOOT = "mostrai_boot"
        private const val CHAVE_BOOT_ID = "boot_id"
        private const val CHAVE_BOOT_COUNT = "boot_count"
        private const val CHAVE_ULTIMO_UPTIME = "ultimo_uptime"
        private const val CHAVE_PROXIMO_SEQ_PREFIXO = "proximo_seq_"

        /** Contrato §8.5: até 6 h por segmento. Um minuto de folga contra arredondamento. */
        const val SEGMENTO_MAXIMO_MS = 6 * 60 * 60 * 1000L - 60_000L

        /** Contrato §8.5: até 200 segmentos por lote. */
        const val TETO_LOTE = 200

        const val STATUS_OK = "ok"
        const val STATUS_INVALIDO = "item_invalido"
        const val STATUS_IGNORADO = "ignorado"
    }
}
