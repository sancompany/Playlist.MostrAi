package br.com.mostrai.player.estado

/**
 * O que o aparelho está fazendo agora, no vocabulário que o heartbeat leva
 * ao admin.
 *
 * O player reporta **fato**, nunca diagnóstico: ele não diz "estou sem
 * sinal" (por definição, uma tela sem rede não consegue dizer nada). Quem
 * classifica é o backend, cruzando este estado com o horário de
 * funcionamento da tela e com quanto tempo faz desde o último heartbeat —
 * ver `docs/player-v2-contract.md`, seção "Estados derivados".
 */
enum class EstadoPlayer {
    /** Exibindo mídia comercial normalmente. */
    PLAYING,

    /** Provisionado e sem erro, mas sem item comercial no ar (institucional, virada). */
    IDLE,

    /** Dentro do regime operacional a tela deveria estar apagada agora. */
    OUT_OF_SCHEDULE,

    /** Sem playlist utilizável — nem do servidor, nem do cache. */
    NO_PLAYLIST,

    /** Mídia não pôde ser baixada, ou baixou e o hash não conferiu. */
    DOWNLOAD_ERROR,

    /** O ExoPlayer falhou ao reproduzir o item. */
    PLAYBACK_ERROR,

    /** Servidor recusou a credencial (401/403) ou não há credencial local. */
    AUTH_ERROR,

    /** Aparelho ainda não provisionado. */
    NOT_PROVISIONED,

    /** Configuração remota recebida não pôde ser aplicada. */
    CONFIG_ERROR,

    /** Há atualização baixada e verificada esperando confirmação de instalação. */
    UPDATE_PENDING,
}
