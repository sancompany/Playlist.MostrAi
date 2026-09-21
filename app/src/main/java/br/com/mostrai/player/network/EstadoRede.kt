package br.com.mostrai.player.network

/**
 * Estado de rede observável pelo painel de manutenção.
 *
 * App de processo único e sempre em primeiro plano (quiosque): estático em
 * memória é suficiente, não precisa de mecanismo de observação.
 */
object EstadoRede {
    @Volatile var contratoNovo: Boolean = false
    @Volatile var ultimaOrigem: String = "—"
    @Volatile var ultimoErroAparelho: Int? = null
    @Volatile var ultimaFalhaTransitoria: String? = null
}
