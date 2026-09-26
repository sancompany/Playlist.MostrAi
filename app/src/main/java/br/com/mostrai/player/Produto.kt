package br.com.mostrai.player

/**
 * O que é igual em toda TV da rede fica aqui, no APK — nunca em
 * provisionamento, preferência, JSON, extra de Intent nem resposta do
 * backend (contrato MVP, `sancompany/MostrAi` `docs/player-mvp-contract.md`
 * §1). Mudar qualquer valor daqui é uma nova build.
 */
object Produto {

    /** Servidor de produção. HTTPS, sem barra final. */
    const val BASE_URL = "https://mostrai.sancocore.com.br"

    /**
     * Rotação do conteúdo, em graus horários, para compensar o painel
     * montado de lado. Se o primeiro teste físico mostrar a imagem de
     * ponta-cabeça, a correção é trocar para 270 numa nova build — nunca
     * tornar isto configurável.
     */
    const val ROTACAO_GRAUS = 90

    const val INTERVALO_HEARTBEAT_MS = 15_000L
    const val INTERVALO_POLL_PLAYLIST_MS = 15 * 60_000L
    const val INTERVALO_ENVIO_POP_MS = 60_000L
}
