package br.com.mostrai.player.network

/**
 * Resultado de uma chamada à API, com as famílias de falha separadas (R8):
 * cada uma pede uma reação diferente — reinstalar, esperar, tentar de novo.
 */
sealed class ResultadoHttp<out T> {
    data class Ok<T>(val valor: T) : ResultadoHttp<T>()

    /** 401 — credencial recusada: a tela volta para a instalação (contrato §4). */
    data class CredencialRecusada(val codigo: Int = 401) : ResultadoHttp<Nothing>()

    /** 403 — tela em reparo ou inativa no cadastro (só `/playlist` e `/played`). */
    object TelaSuspensa : ResultadoHttp<Nothing>()

    /** 429 — servidor pediu para esperar. */
    data class Limitado(val segundos: Int) : ResultadoHttp<Nothing>()

    /** 5xx — problema do lado de lá, transitório por definição. */
    data class ErroServidor(val codigo: Int) : ResultadoHttp<Nothing>()

    /** Timeout, DNS, socket — a TV não falou com ninguém. */
    data class SemRede(val motivo: String) : ResultadoHttp<Nothing>()

    /** Respondeu, mas não o que o contrato diz (corpo ilegível, código inesperado). */
    data class RespostaInvalida(val motivo: String) : ResultadoHttp<Nothing>()

    /** Não dá nem para tentar: aparelho sem credencial. */
    object SemCredencial : ResultadoHttp<Nothing>()

    /** Código curto e estável para o diário e para o heartbeat. */
    fun codigoDiagnostico(): String = when (this) {
        is Ok -> "OK"
        is CredencialRecusada -> "AUTH_$codigo"
        is TelaSuspensa -> "SUSPENSA"
        is Limitado -> "LIMITADO"
        is ErroServidor -> "SERVIDOR_$codigo"
        is SemRede -> "SEM_REDE"
        is RespostaInvalida -> "RESPOSTA_INVALIDA"
        is SemCredencial -> "SEM_CREDENCIAL"
    }
}
