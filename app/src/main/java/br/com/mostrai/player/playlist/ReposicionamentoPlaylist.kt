package br.com.mostrai.player.playlist

/**
 * Decide, a cada playlist nova (busca periódica, virada de hora ou início
 * frio), se o app deve reiniciar a exibição agora e em que índice — sem
 * tocar em `PlayerActivity`, pra poder ser testado sem Android.
 *
 * Três casos, nesta ordem:
 * 1. Início frio ou a janela mudou: reposiciona por tempo (decisão 7.1,
 *    `PosicaoNaPlaylist`) e reinicia agora.
 * 2. Contrato antigo (modo degradado): `itemProgramacaoId` não existe
 *    (sempre nulo) e `janelaInicio` também não — reancorar por id é
 *    impossível, e cair no cálculo por tempo sempre devolveria o item 0.
 *    Reiniciar do zero a cada busca periódica (15 em 15 min) cortaria a
 *    exibição em andamento no meio, sem `STATE_ENDED` — pior que o viés de
 *    distribuição que a retomada por tempo existe pra evitar. Sem id pra
 *    reancorar, o certo é não mexer: mantém o índice atual (clampado ao
 *    novo tamanho) e deixa o item em andamento terminar sozinho.
 * 3. Contrato novo, mesma janela: reancora pelo `itemProgramacaoId` do item
 *    em exibição. Se ele ainda existe na playlist nova, só atualiza o
 *    índice, sem reiniciar. Se saiu da elegibilidade, reposiciona por tempo
 *    e reinicia.
 */
object ReposicionamentoPlaylist {

    data class Decisao(val indice: Int, val reiniciarAgora: Boolean)

    fun decidir(
        playlistAnterior: Playlist,
        indiceAnterior: Int,
        playlistNova: Playlist,
        forcarReposicionamento: Boolean,
        trocouDeJanela: Boolean,
        indiceInicialPorTempo: () -> Int,
    ): Decisao {
        if (playlistNova.itens.isEmpty()) return Decisao(0, reiniciarAgora = false)

        if (forcarReposicionamento || trocouDeJanela) {
            return Decisao(indiceInicialPorTempo(), reiniciarAgora = true)
        }

        if (playlistNova.modoDegradado) {
            val indice = indiceAnterior.coerceIn(0, playlistNova.itens.lastIndex)
            return Decisao(indice, reiniciarAgora = false)
        }

        val idItemAtual = playlistAnterior.itens.getOrNull(indiceAnterior)?.itemProgramacaoId
        val novoIndice = idItemAtual
            ?.let { id -> playlistNova.itens.indexOfFirst { it.itemProgramacaoId == id } }
            ?: -1
        return if (novoIndice >= 0) {
            Decisao(novoIndice, reiniciarAgora = false)
        } else {
            Decisao(indiceInicialPorTempo(), reiniciarAgora = true)
        }
    }
}
