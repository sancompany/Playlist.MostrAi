package br.com.mostrai.player.proof

/**
 * Uma exibição em processo de virar comprovante.
 *
 * Nasce sem `terminadoEm`, ANTES do play() (decisão 3, seção 4) — só passa a
 * ser elegível para envio quando ganha `terminadoEm`, ou seja, quando a
 * exibição realmente chega ao fim (STATE_ENDED). A decisão 4 vive aqui, no
 * armazenamento, não só no envio.
 */
data class EventoExibicao(
    val execucaoId: String,
    val janelaId: String?,
    val itemProgramacaoId: String?,
    val criativoId: String?,
    val anuncianteId: String?,
    /** Playlist estava em modo degradado (array antigo) quando este evento nasceu. */
    val formatoLegado: Boolean,
    /** ISO 8601 com offset, relógio do aparelho — auditoria apenas. */
    val iniciadoEm: String,
    val terminadoEm: String?,
    val tentativas: Int,
    val proximoEnvioElegivelEm: Long,
    val criadoEmMs: Long,
    /**
     * Preenchido quando o servidor rejeitou este evento de forma definitiva
     * (HTTP 400 isolado por [br.com.mostrai.player.proof.FilaProofOfPlay]).
     * Sai da fila de envio mas continua visível no diagnóstico até expirar.
     */
    val quarentenaMotivo: String? = null,
)
