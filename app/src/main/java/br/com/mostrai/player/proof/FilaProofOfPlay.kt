package br.com.mostrai.player.proof

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Fila durável de proof-of-play: cria a linha antes do play(), envia em lote
 * quando o item termina de verdade, e só remove a linha quando o servidor
 * responde um status definitivo — ou nos outros dois casos fechados na
 * seção 6.5: payload malformado (400, isolado item a item) e expiração local
 * de 7 dias. Nunca por timeout, 5xx, erro de socket ou reinício do app.
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
     * Cria a linha ANTES do play(), com o execucaoId já definido (decisão 3,
     * seção 4). Retorna null para itens que não contam (institucional,
     * autoanúncio) — esses nunca entram na fila — e quando o banco não
     * aceita a linha: o item toca, sem comprovante.
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
                anuncianteId = item.anuncianteId,
                formatoLegado = playlist.modoDegradado,
                iniciadoEm = OffsetDateTime.now().toString(),
                terminadoEm = null,
                tentativas = 0,
                proximoEnvioElegivelEm = 0L,
                criadoEmMs = System.currentTimeMillis(),
            )
        )
        execucaoId
    }

    /** Chamado só no STATE_ENDED — aqui a exibição vira comprovante elegível. */
    @Synchronized
    fun registrarFim(execucaoId: String) {
        seguro(Unit) { db.marcarTerminado(execucaoId, OffsetDateTime.now().toString()) }
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
        seguro(Unit) { db.remover(execucaoId) }
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
                removerExpirados()

                val agora = System.currentTimeMillis()
                val elegiveis = db.elegiveisParaEnvio(agora, LIMITE_LOTE)
                if (elegiveis.isEmpty()) return

                val (legados, novos) = elegiveis.partition { it.formatoLegado }
                if (novos.isNotEmpty()) enviarLoteNovo(novos)
                legados.forEach { enviarUmLegado(it) }
            }
        } finally {
            enviando.set(false)
        }
    }

    private fun enviarLoteNovo(eventos: List<EventoExibicao>) {
        when (val resposta = api.enviarLote(eventos)) {
            is MostraiApi.RespostaPlayed.Sucesso -> {
                val paraRemover = mutableListOf<String>()
                for (evento in eventos) {
                    val status = resposta.resultadosPorId[evento.execucaoId]
                    when {
                        status != null && status in STATUS_DEFINITIVOS -> paraRemover.add(evento.execucaoId)
                        status != null -> {
                            Log.w(TAG, "status desconhecido '$status' para ${evento.execucaoId}, mantendo na fila")
                            adiarComBackoff(evento)
                        }
                        else -> adiarComBackoff(evento) // não veio no lote: tenta de novo depois
                    }
                }
                db.removerLote(paraRemover)
            }
            is MostraiApi.RespostaPlayed.ErroPayload -> isolarPayloadRejeitado(eventos)
            is MostraiApi.RespostaPlayed.ErroAparelho -> {
                Log.w(TAG, "erro do aparelho (${resposta.codigo}) ao enviar proof-of-play, fila mantida")
                // Não descarta nada — problema do aparelho, fica visível no
                // painel. Mas reagenda (ROB-005): sem isso, o lote inteiro ia
                // de novo ao fim de cada exibição só para voltar 401.
                eventos.forEach { adiarComBackoff(it) }
            }
            is MostraiApi.RespostaPlayed.RespeitarEspera -> {
                val proximo = fimDaEspera(resposta.segundos)
                eventos.forEach { db.adiarReenvio(it.execucaoId, proximo, it.tentativas + 1) }
            }
            is MostraiApi.RespostaPlayed.Transitorio -> eventos.forEach { adiarComBackoff(it) }
            is MostraiApi.RespostaPlayed.SucessoLegado -> Unit // não ocorre no envio em lote
        }
    }

    /**
     * Busca binária pelo evento que o servidor rejeita (R4).
     *
     * Um 400 num lote não diz QUAL evento está malformado. A versão anterior
     * descartava os 50 de uma vez — jogava fora até 49 comprovantes bons por
     * causa de um ruim, sem deixar rastro. Aqui o lote é partido ao meio e
     * reenviado até sobrar um único evento; só esse vai para quarentena, e
     * os irmãos saudáveis voltam pela porta normal.
     *
     * Custo: no pior caso ~2·log2(50) ≈ 12 requisições, uma vez, para salvar
     * o resto do lote. Um lote de tamanho 1 que leva 400 é conclusivo: o
     * problema é aquele evento.
     */
    private fun isolarPayloadRejeitado(eventos: List<EventoExibicao>) {
        if (eventos.size == 1) {
            val evento = eventos.first()
            Log.e(TAG, "evento ${evento.execucaoId} rejeitado como malformado (400), em quarentena")
            db.marcarQuarentena(evento.execucaoId, MOTIVO_QUARENTENA)
            incrementarPerdas(1)
            return
        }

        val meio = eventos.size / 2
        enviarLoteNovo(eventos.subList(0, meio))
        enviarLoteNovo(eventos.subList(meio, eventos.size))
    }

    private fun enviarUmLegado(evento: EventoExibicao) {
        val anuncianteId = evento.anuncianteId
        if (anuncianteId == null) {
            db.marcarQuarentena(evento.execucaoId, "sem anuncianteId no formato legado")
            incrementarPerdas(1)
            return
        }
        when (val resposta = api.enviarLegado(anuncianteId)) {
            is MostraiApi.RespostaPlayed.SucessoLegado -> db.remover(evento.execucaoId)
            is MostraiApi.RespostaPlayed.ErroPayload -> {
                db.marcarQuarentena(evento.execucaoId, MOTIVO_QUARENTENA)
                incrementarPerdas(1)
            }
            is MostraiApi.RespostaPlayed.ErroAparelho -> adiarComBackoff(evento) // fila mantida, reagendada
            is MostraiApi.RespostaPlayed.RespeitarEspera -> {
                db.adiarReenvio(evento.execucaoId, fimDaEspera(resposta.segundos), evento.tentativas + 1)
            }
            is MostraiApi.RespostaPlayed.Transitorio -> adiarComBackoff(evento)
            is MostraiApi.RespostaPlayed.Sucesso -> Unit // não ocorre no envio legado
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
     * O teto de [TAMANHO_MAXIMO_FILA] cobre ~5,8 dias de tela 24h com
     * criativos de 10s — o cenário real de "loja fechou no feriado com a
     * internet caída". O teto anterior (5.000) saturava em menos de 14h.
     */
    private fun limitarTamanho() {
        if (db.contarPendentes() < TAMANHO_MAXIMO_FILA) return
        db.proximoADescartar()?.let {
            val eraComprovante = db.aguardaEnvio(it)
            db.remover(it)
            if (eraComprovante) incrementarPerdas(1)
        }
    }

    /** Só comprovante conta como perda — ver [ProofOfPlayDb.contarComprovantesAntesDe]. */
    private fun removerExpirados() {
        val limite = System.currentTimeMillis() - HORIZONTE_EXPIRACAO_MS
        val comprovantes = db.contarComprovantesAntesDe(limite)
        db.removerExpirados(limite)
        if (comprovantes > 0) incrementarPerdas(comprovantes)
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
        const val LIMITE_LOTE = 50

        /**
         * ~5,8 dias de tela 24h com criativos de 10s. Cada linha ocupa ~200
         * bytes, então o teto inteiro é ~10 MB de SQLite — irrelevante para o
         * aparelho, e é o que separa "ficou sem internet no feriado" de
         * "perdeu a receita do feriado".
         */
        const val TAMANHO_MAXIMO_FILA = 50_000
        const val HORIZONTE_EXPIRACAO_MS = 7L * 24 * 60 * 60 * 1000

        /** Teto do `Retry-After` — igual ao maior degrau do backoff. */
        const val ESPERA_MAXIMA_SEGUNDOS = 30 * 60

        /** Acima disto o admin mostra atenção; ver docs/player-v2-contract.md. */
        const val LIMIAR_ATENCAO = 2_000
        const val LIMIAR_ALERTA = 10_000

        const val MOTIVO_QUARENTENA = "rejeitado pelo servidor (400)"

        val STATUS_DEFINITIVOS = setOf(
            "contabilizado", "duplicado", "teto_atingido",
            "janela_desconhecida", "item_invalido", "janela_expirada",
        )
        val BACKOFF_MS = listOf(5_000L, 15_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L)
    }
}
