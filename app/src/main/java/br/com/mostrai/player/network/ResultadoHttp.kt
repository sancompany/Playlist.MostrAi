package br.com.mostrai.player.network

/**
 * Resultado de uma chamada à API, com as famílias de falha separadas (R8).
 *
 * A versão anterior colapsava tudo em `null`: uma chave revogada (401) ficava
 * indistinguível de um servidor fora do ar (500) e de um cabo solto
 * (timeout). Como cada uma exige uma ação diferente — reprovisionar, esperar,
 * ignorar — o tipo precisa preservar a diferença até quem decide.
 */
sealed class ResultadoHttp<out T> {
    data class Ok<T>(val valor: T) : ResultadoHttp<T>()

    /** 401/403 — a credencial não serve. Nenhuma retentativa resolve. */
    data class ErroAutenticacao(val codigo: Int) : ResultadoHttp<Nothing>()

    /**
     * 404 — nesta API significa "rota inexistente", que é exatamente como um
     * backend V1 responde a um endpoint V2. Sinal de degradar, não de erro.
     */
    object NaoEncontrado : ResultadoHttp<Nothing>()

    /** 429 — servidor pediu para esperar. */
    data class Limitado(val segundos: Int) : ResultadoHttp<Nothing>()

    /** 5xx — problema do lado de lá, transitório por definição. */
    data class ErroServidor(val codigo: Int) : ResultadoHttp<Nothing>()

    /** Timeout, DNS, socket — a TV não falou com ninguém. */
    data class SemRede(val motivo: String) : ResultadoHttp<Nothing>()

    /** Respondeu 2xx, mas o corpo não é o que o contrato diz. */
    data class RespostaInvalida(val motivo: String) : ResultadoHttp<Nothing>()

    /** Não dá nem para tentar: falta baseUrl, dispositivoId ou credencial. */
    data class SemCredencial(val motivo: String) : ResultadoHttp<Nothing>()

    val ok: T? get() = (this as? Ok)?.valor

    /** Código curto e estável para o diário e para o heartbeat. */
    fun codigoDiagnostico(): String = when (this) {
        is Ok -> "OK"
        is ErroAutenticacao -> "AUTH_$codigo"
        is NaoEncontrado -> "NAO_ENCONTRADO"
        is Limitado -> "LIMITADO"
        is ErroServidor -> "SERVIDOR_$codigo"
        is SemRede -> "SEM_REDE"
        is RespostaInvalida -> "RESPOSTA_INVALIDA"
        is SemCredencial -> "SEM_CREDENCIAL"
    }
}
