package br.com.mostrai.player

/**
 * Só na variante debug: os testes (que rodam contra ela) apontam a API para
 * um servidor local. Nenhuma entrada externa chega aqui — nem preferência,
 * nem Intent, nem arquivo —, e a build de release tem outra versão deste
 * objeto, sem `var` (`src/release`).
 */
internal object HostDaApi {
    @Volatile
    var base: String = Produto.BASE_URL
}
