package br.com.mostrai.player

/** Na build de release o servidor é sempre [Produto.BASE_URL]. Sem ponto de troca. */
internal object HostDaApi {
    val base: String get() = Produto.BASE_URL
}
