package br.com.mostrai.player.proof

import android.content.Context
import android.util.Log
import br.com.mostrai.player.network.MostraiApi
import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.min

/**
 * Fila durável de proof-of-play: cria a linha antes do play(), envia em lote
 * quando o item termina de verdade, e só remove a linha quando o servidor
 * responde um status definitivo — ou nos outros dois casos fechados na
 * seção 6.5: payload malformado (400) e expiração local de 7 dias. Nunca por
 * timeout, 5xx, erro de socket ou reinício do app (seção 6.5).
 *
 * Chamar sempre de uma thread de fundo — faz E/S de disco e de rede.
 */
class FilaProofOfPlay(
    context: Context,
    private val api: MostraiApi,
) {
    private val db = ProofOfPlayDb(context)
    private val prefsPerdas = context.applicationContext
        .getSharedPreferences(ProofOfPlayDb.PREFS_PERDAS, Context.MODE_PRIVATE)

    /**
     * Cria a linha ANTES do play(), com o execucaoId já definido (decisão 3,
     * seção 4). Retorna null para itens que não contam (institucional,
     * autoanúncio) — esses nunca entram na fila.
     */
    @Synchronized
    fun registrarInicio(item: ItemPlaylist, playlist: Playlist): String? {
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
        return execucaoId
    }

    /** Chamado só no STATE_ENDED — aqui a exibição vira comprovante elegível. */
    @Synchronized
    fun registrarFim(execucaoId: String) {
        db.marcarTerminado(execucaoId, OffsetDateTime.now().toString())
    }

    /**
     * Reprodução falhou antes de terminar: a linha nunca teve terminadoEm,
     * então nunca foi uma alegação de exibição completa. Descartá-la é
     * correto, não é perda de comprovante — não havia comprovante nenhum
     * ainda para perder.
     */
    @Synchronized
    fun registrarFalha(execucaoId: String) {
        db.remover(execucaoId)
    }

    fun pendentes(): Int = db.contarPendentes()

    fun perdas(): Int = prefsPerdas.getInt(ProofOfPlayDb.CHAVE_PERDAS, 0)

    /**
     * Tenta enviar o que estiver elegível. Chamar no fim de cada exibição,
     * ao recuperar rede, ao iniciar o app, e por temporizador enquanto
     * houver fila (decisão 6.4).
     */
    @Synchronized
    fun tentarEnviar() {
        removerExpirados()

        val agora = System.currentTimeMillis()
        val elegiveis = db.elegiveisParaEnvio(agora, LIMITE_LOTE)
        if (elegiveis.isEmpty()) return

        val (legados, novos) = elegiveis.partition { it.formatoLegado }
        if (novos.isNotEmpty()) enviarLoteNovo(novos)
        legados.forEach { enviarUmLegado(it) }
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
            is MostraiApi.RespostaPlayed.ErroPayload -> {
                // Definitivo: esse payload nunca vai virar válido (seção 6.5).
                Log.e(TAG, "lote rejeitado como malformado (400), descartando ${eventos.size} evento(s)")
                db.removerLote(eventos.map { it.execucaoId })
                incrementarPerdas(eventos.size)
            }
            is MostraiApi.RespostaPlayed.ErroAparelho -> {
                Log.w(TAG, "erro do aparelho (${resposta.codigo}) ao enviar proof-of-play, fila mantida")
                // Não descarta nada — problema do aparelho, fica visível no painel.
            }
            is MostraiApi.RespostaPlayed.RespeitarEspera -> {
                val proximo = System.currentTimeMillis() + resposta.segundos * 1000L
                eventos.forEach { db.adiarReenvio(it.execucaoId, proximo, it.tentativas + 1) }
            }
            is MostraiApi.RespostaPlayed.Transitorio -> eventos.forEach { adiarComBackoff(it) }
            is MostraiApi.RespostaPlayed.SucessoLegado -> Unit // não ocorre no envio em lote
        }
    }

    private fun enviarUmLegado(evento: EventoExibicao) {
        val anuncianteId = evento.anuncianteId
        if (anuncianteId == null) {
            db.remover(evento.execucaoId)
            incrementarPerdas(1)
            return
        }
        when (val resposta = api.enviarLegado(anuncianteId)) {
            is MostraiApi.RespostaPlayed.SucessoLegado -> db.remover(evento.execucaoId)
            is MostraiApi.RespostaPlayed.ErroPayload -> {
                db.remover(evento.execucaoId)
                incrementarPerdas(1)
            }
            is MostraiApi.RespostaPlayed.ErroAparelho -> Unit // fila mantida
            is MostraiApi.RespostaPlayed.RespeitarEspera -> {
                val proximo = System.currentTimeMillis() + resposta.segundos * 1000L
                db.adiarReenvio(evento.execucaoId, proximo, evento.tentativas + 1)
            }
            is MostraiApi.RespostaPlayed.Transitorio -> adiarComBackoff(evento)
            is MostraiApi.RespostaPlayed.Sucesso -> Unit // não ocorre no envio legado
        }
    }

    /** execucaoId jamais é regerado numa retentativa (decisão 6.4) — só reagenda. */
    private fun adiarComBackoff(evento: EventoExibicao) {
        val tentativas = evento.tentativas + 1
        val atrasoMs = BACKOFF_MS.getOrElse(min(tentativas, BACKOFF_MS.size) - 1) { BACKOFF_MS.last() }
        db.adiarReenvio(evento.execucaoId, System.currentTimeMillis() + atrasoMs, tentativas)
    }

    /** Fila limitada: no estouro, descarta o mais antigo e conta a perda (decisão 6.4). */
    private fun limitarTamanho() {
        if (db.contarPendentes() < TAMANHO_MAXIMO_FILA) return
        db.maisAntigoNaoEnviado()?.let {
            db.remover(it)
            incrementarPerdas(1)
        }
    }

    private fun removerExpirados() {
        val limite = System.currentTimeMillis() - HORIZONTE_EXPIRACAO_MS
        val removidos = db.removerExpirados(limite)
        if (removidos > 0) incrementarPerdas(removidos)
    }

    private fun incrementarPerdas(quantidade: Int) {
        prefsPerdas.edit().putInt(ProofOfPlayDb.CHAVE_PERDAS, perdas() + quantidade).apply()
    }

    private companion object {
        const val TAG = "FilaProofOfPlay"
        const val LIMITE_LOTE = 50
        const val TAMANHO_MAXIMO_FILA = 5_000
        const val HORIZONTE_EXPIRACAO_MS = 7L * 24 * 60 * 60 * 1000

        val STATUS_DEFINITIVOS = setOf(
            "contabilizado", "duplicado", "teto_atingido",
            "janela_desconhecida", "item_invalido", "janela_expirada",
        )
        val BACKOFF_MS = listOf(5_000L, 15_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L)
    }
}
