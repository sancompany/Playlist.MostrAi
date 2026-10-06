package br.com.mostrai.player.proof

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import br.com.mostrai.player.estado.DiarioBordo
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.network.ResultadoHttp
import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Fila durável de proof-of-play: cria a linha antes do play(), envia em lote
 * quando o item termina de verdade, e só remove um comprovante quando o
 * servidor responde um dos 6 status finais (contrato §8) — o ACK. Nunca por
 * idade (Ponto Móvel, 02/10/2026: a tela pode passar dias sem internet; o
 * servidor responde `janela_expirada` quando for tarde, pelo relógio DELE),
 * nunca por timeout, 5xx, 401/403, erro de socket ou reinício do app. A
 * única outra saída é a fila cheia — e essa fica registrada no diário.
 *
 * Chamar sempre de uma thread de fundo — faz E/S de disco e de rede.
 *
 * **Nenhuma operação pública lança `SQLiteException`** (BUG-004): disco
 * cheio ou banco ilegível derrubavam o processo a cada item, e o watchdog o
 * reabria para cair de novo. Sem banco não há onde guardar comprovante, e a
 * escolha é entre tela preta e tocar sem cobrar — o anunciante já está sem
 * comprovante nos dois casos, a loja pelo menos não fica com a tela apagada.
 */
class FilaProofOfPlay(
    context: Context,
    private val api: MostraiApi,
    private val diario: DiarioBordo? = null,
    /**
     * Instante de `iniciadoEm`/`terminadoEm`: o relógio confiável (âncora do
     * servidor + monotônico) quando há, o de parede só sem ele. Comercial só
     * toca com relógio confiável, então na prática é sempre o do servidor —
     * a TV que voltou do reboot com a hora errada não carimba o comprovante
     * com ela.
     */
    private val relogio: () -> Long? = { null },
) {
    private val db = ProofOfPlayDb(context)
    private val prefsPerdas = context.applicationContext
        .getSharedPreferences(ProofOfPlayDb.PREFS_PERDAS, Context.MODE_PRIVATE)

    /**
     * Um envio de cada vez, **separado** da trava das operações de banco
     * (BUG-007). Antes, `tentarEnviar` era `@Synchronized` no mesmo monitor
     * de `registrarInicio`, e segurava esse monitor durante a chamada de
     * rede. Como o player envia ao fim de toda exibição e o próximo item
     * registra início logo em seguida, toda transição esperava o round-trip
     * do envio anterior — até 25s de tela congelada numa internet dando
     * timeout. Agora a rede roda sem trava de banco; esta flag só impede dois
     * envios simultâneos de mandarem o mesmo evento.
     */
    private val enviando = AtomicBoolean(false)

    /**
     * Exibições no ar deste processo: a linha já existe e ainda não tem
     * `terminado_em`, igual a um órfão. Nem a limpeza por idade (que usa o
     * relógio de parede, e ele pode saltar dias para frente) nem a fila
     * cheia (que descarta órfão primeiro) podem levá-la — no fim ela vira
     * comprovante, e [registrarFim] não teria mais o que marcar.
     */
    private val emAndamento: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Fotografia da fila para o heartbeat e para o painel. */
    data class Resumo(val aguardandoEnvio: Int, val total: Int, val quarentena: Int, val maisAntigoMs: Long?)

    @Synchronized
    fun resumo(): Resumo = seguro(Resumo(0, 0, 0, null)) {
        Resumo(
            aguardandoEnvio = db.contarAguardandoEnvio(),
            total = db.contarPendentes(),
            quarentena = db.contarQuarentena(),
            maisAntigoMs = db.maisAntigoAguardandoEnvioMs(),
        )
    }

    /**
     * Cria a linha ANTES do play(), com o execucaoId já definido. Ela só vira
     * comprovante em [registrarFim] (STATE_ENDED). Retorna null para itens
     * que não contam (`contabiliza: false`) — esses nunca entram na fila — e
     * quando o banco não aceita a linha: o item toca, sem comprovante.
     */
    @Synchronized
    fun registrarInicio(item: ItemPlaylist, playlist: Playlist): String? = seguro(null) {
        if (!item.contabiliza) return null

        limitarTamanho()

        val execucaoId = UUID.randomUUID().toString()
        db.inserir(
            EventoExibicao(
                execucaoId = execucaoId,
                janelaId = playlist.janelaId,
                itemProgramacaoId = item.itemProgramacaoId,
                criativoId = item.criativoId,
                iniciadoEm = agoraIso(),
                terminadoEm = null,
                tentativas = 0,
                proximoEnvioElegivelEm = 0L,
                criadoEmMs = System.currentTimeMillis(),
            )
        )
        emAndamento += execucaoId
        execucaoId
    }

    /** Chamado só no STATE_ENDED — aqui a exibição vira comprovante elegível. */
    @Synchronized
    fun registrarFim(execucaoId: String) {
        try {
            seguro(Unit) { db.marcarTerminado(execucaoId, agoraIso()) }
        } finally {
            emAndamento -= execucaoId
        }
    }

    /**
     * Reprodução falhou ou foi interrompida antes de terminar: a linha nunca
     * teve terminadoEm, então nunca foi uma alegação de exibição completa.
     * Descartá-la é correto, não é perda de comprovante — não havia
     * comprovante nenhum ainda para perder. Chamar isto em TODO caminho que
     * abandona uma exibição (R5), senão a linha fica órfã ocupando a fila até
     * expirar em 7 dias.
     */
    @Synchronized
    fun registrarFalha(execucaoId: String) {
        try {
            seguro(Unit) { db.remover(execucaoId) }
        } finally {
            emAndamento -= execucaoId
        }
    }

    private fun agoraIso(): String {
        val ms = relogio() ?: System.currentTimeMillis()
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toString()
    }

    fun pendentes(): Int = seguro(0) { db.contarAguardandoEnvio() }

    fun perdas(): Int = prefsPerdas.getInt(ProofOfPlayDb.CHAVE_PERDAS, 0)

    /**
     * Tenta enviar o que estiver elegível. Chamar no fim de cada exibição,
     * ao recuperar rede, ao iniciar o app, e por temporizador enquanto
     * houver fila (decisão 6.4).
     */
    fun tentarEnviar() {
        // Já tem envio em andamento: ele vai buscar o que estiver elegível, e
        // o próximo ciclo (1 min, ou o fim da próxima exibição) pega o resto.
        if (!enviando.compareAndSet(false, true)) return
        try {
            seguro(Unit) {
                removerSemValor()

                // Depois de dias offline a fila pode ter milhares de eventos:
                // lotes em sequência, um por vez, enquanto o servidor aceita —
                // nunca em paralelo (sem tempestade de requisições).
                repeat(MAX_LOTES_POR_RODADA) {
                    val elegiveis = db.elegiveisParaEnvio(System.currentTimeMillis(), LIMITE_LOTE)
                    if (elegiveis.isEmpty()) return
                    if (!enviarLote(elegiveis)) return
                }
            }
        } finally {
            enviando.set(false)
        }
    }

    /**
     * A rede voltou: o que esperava a vez no backoff (até 30 min) sai agora.
     * O backoff serve para não insistir com a rede fora; com ela de volta,
     * segurar o comprovante só atrasa o admin.
     */
    fun redeVoltou() {
        seguro(Unit) { db.liberarParaEnvio() }
        tentarEnviar()
    }

    /** Acumula, ao longo de uma bisseção, se algum pedaço do lote foi aceito. */
    private class Bissecao {
        var algumAceito = false
        val recusados = mutableListOf<EventoExibicao>()
    }

    /** `true` = o servidor aceitou o lote (e cabe tentar o próximo na mesma rodada). */
    private fun enviarLote(eventos: List<EventoExibicao>, bissecao: Bissecao? = null): Boolean {
        when (val resposta = api.enviarLote(eventos)) {
            is ResultadoHttp.Ok -> {
                bissecao?.algumAceito = true
                val paraRemover = mutableListOf<String>()
                for (evento in eventos) {
                    val status = resposta.valor[evento.execucaoId]
                    when {
                        status != null && status in STATUS_FINAIS -> paraRemover.add(evento.execucaoId)
                        status != null -> {
                            Log.w(TAG, "status desconhecido '$status' para ${evento.execucaoId}, mantendo na fila")
                            adiarComBackoff(evento)
                        }
                        else -> adiarComBackoff(evento) // não veio no lote: tenta de novo depois
                    }
                }
                db.removerLote(paraRemover)
                return true
            }
            // 400: lote malformado; 413: corpo acima de 100 KB. Nos dois, dividir resolve.
            is ResultadoHttp.RespostaInvalida -> {
                if (resposta.motivo == "HTTP 400" || resposta.motivo == "HTTP 413") {
                    isolarLoteRecusado(eventos, bissecao)
                } else {
                    eventos.forEach { adiarComBackoff(it) }
                }
            }
            is ResultadoHttp.Limitado -> {
                val proximo = fimDaEspera(resposta.segundos)
                eventos.forEach { db.adiarReenvio(it.execucaoId, proximo, it.tentativas + 1) }
            }
            // 401 (a Activity volta para a instalação), 403 (tela em reparo),
            // 5xx, rede: o comprovante fica. Reagenda (ROB-005) — sem isso o
            // lote inteiro iria de novo ao fim de cada exibição.
            else -> eventos.forEach { adiarComBackoff(it) }
        }
        return false
    }

    /**
     * Busca binária pelo evento que o servidor rejeita (R4).
     *
     * Um 400 (ou 413) num lote não diz QUAL evento o servidor recusa. O lote
     * é partido ao meio e reenviado até sobrarem eventos sozinhos. Mas um
     * evento só vai para a quarentena se algum irmão do MESMO lote passou:
     * pelo contrato, evento ruim volta `item_invalido` e 400 só existe para
     * lote malformado — que o Player não produz. Se nenhum pedaço passou, o
     * problema é do servidor ou de quem está no caminho (deploy com
     * regressão, proxy), não dos comprovantes: todos voltam para o backoff e
     * o diário registra. Antes, um 400 sistêmico de dez minutos punha a fila
     * inteira em quarentena, para sempre.
     */
    private fun isolarLoteRecusado(eventos: List<EventoExibicao>, bissecao: Bissecao?) {
        if (eventos.size == 1 && bissecao != null) {
            bissecao.recusados += eventos.first()
            return
        }
        val raiz = bissecao ?: Bissecao()
        if (eventos.size == 1) {
            raiz.recusados += eventos.first()
        } else {
            val meio = eventos.size / 2
            enviarLote(eventos.subList(0, meio), raiz)
            enviarLote(eventos.subList(meio, eventos.size), raiz)
        }
        if (bissecao != null) return

        if (raiz.algumAceito) {
            raiz.recusados.forEach {
                Log.e(TAG, "evento ${it.execucaoId} recusado sozinho pelo servidor, em quarentena")
                db.marcarQuarentena(it.execucaoId, MOTIVO_QUARENTENA)
            }
            incrementarPerdas(raiz.recusados.size)
        } else {
            raiz.recusados.forEach { adiarComBackoff(it) }
            diario?.registrar(DiarioBordo.Codigo.FILA_RECUSADA, "servidor recusou o lote inteiro (400/413); comprovantes mantidos")
        }
    }

    /**
     * `Retry-After` obedecido, mas com teto (BUG-018). Um 429 com espera
     * absurda — proxy ou CDN mal configurado — adiava o lote para além do
     * horizonte de 7 dias, e o comprovante expirava sem nunca ser reenviado.
     */
    private fun fimDaEspera(segundos: Int): Long =
        System.currentTimeMillis() + segundos.coerceIn(0, ESPERA_MAXIMA_SEGUNDOS) * 1000L

    /** execucaoId jamais é regerado numa retentativa (decisão 6.4) — só reagenda. */
    private fun adiarComBackoff(evento: EventoExibicao) {
        val tentativas = evento.tentativas + 1
        val atrasoMs = BACKOFF_MS.getOrElse(min(tentativas, BACKOFF_MS.size) - 1) { BACKOFF_MS.last() }
        db.adiarReenvio(evento.execucaoId, System.currentTimeMillis() + atrasoMs, tentativas)
    }

    /**
     * Fila limitada: no estouro, descarta na ordem de menor valor primeiro
     * (órfão → quarentena → comprovante), nunca o mais antigo cegamente (R2).
     *
     * O teto de [TAMANHO_MAXIMO_FILA] cobre semanas de tela ligada 24 h
     * (Ponto Móvel: dias sem internet num evento). Chegar nele já é
     * incidente — por isso cada comprovante descartado vai para o diário.
     */
    private fun limitarTamanho() {
        if (db.contarPendentes() < TAMANHO_MAXIMO_FILA) return
        db.proximoADescartar(emAndamento.toList())?.let {
            val eraComprovante = db.aguardaEnvio(it)
            db.remover(it)
            if (eraComprovante) {
                incrementarPerdas(1)
                diario?.registrar(DiarioBordo.Codigo.FILA_CHEIA, "fila de comprovantes cheia: o mais antigo foi descartado")
            }
        }
    }

    /**
     * Só o que nunca será comprovante sai por idade: linha órfã (exibição
     * que começou e não terminou) e quarentena (já contada como perda).
     * Comprovante terminado nunca sai daqui — só pelo ACK do servidor.
     */
    @Synchronized
    private fun removerSemValor() {
        db.removerSemValorAntesDe(System.currentTimeMillis() - HORIZONTE_SEM_VALOR_MS, emAndamento.toList())
    }

    private inline fun <T> seguro(padrao: T, bloco: () -> T): T = try {
        bloco()
    } catch (e: SQLiteException) {
        Log.e(TAG, "fila de proof-of-play indisponível: ${e.javaClass.simpleName}")
        padrao
    }

    /** Ler-somar-gravar: sincronizado porque o envio e o registro de início rodam em paralelo. */
    @Synchronized
    private fun incrementarPerdas(quantidade: Int) {
        prefsPerdas.edit().putInt(ProofOfPlayDb.CHAVE_PERDAS, perdas() + quantidade).apply()
    }

    companion object {
        private const val TAG = "FilaProofOfPlay"
        /** ~15 KB por lote: o contrato aceita 500, mas o corpo tem teto de 100 KB (413). */
        const val LIMITE_LOTE = 50

        /**
         * ~26 dias de tela 24 h com criativos de 15 s. Cada linha ocupa uns
         * 400 bytes com índice: ~60 MB no teto — a reserva de disco do cache
         * de mídia ([br.com.mostrai.player.cache.CacheMidia.reservaBytes])
         * existe para que o vídeo nunca dispute espaço com o comprovante.
         */
        const val TAMANHO_MAXIMO_FILA = 150_000

        /**
         * Órfão e quarentena — o que nunca vira comprovante — saem depois
         * disto. Comprovante não tem prazo local (ver [removerSemValor]).
         */
        const val HORIZONTE_SEM_VALOR_MS = (7L * 24 + 1) * 60 * 60 * 1000

        /** Lotes em sequência por rodada de envio: 1.000 eventos, sem rajada. */
        const val MAX_LOTES_POR_RODADA = 20

        /** Teto do `Retry-After` — igual ao maior degrau do backoff. */
        const val ESPERA_MAXIMA_SEGUNDOS = 30 * 60

        /** Fila acima disto vira evento no diário — e, pelo heartbeat, erro no admin. */
        const val LIMIAR_ALERTA = 10_000

        const val MOTIVO_QUARENTENA = "recusado pelo servidor (400/413)"

        /** Os 6 status finais do contrato (§8): o evento sai da fila. */
        val STATUS_FINAIS = setOf(
            "contabilizado", "duplicado", "teto_atingido",
            "janela_desconhecida", "item_invalido", "janela_expirada",
        )
        val BACKOFF_MS = listOf(5_000L, 15_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L)
    }
}
