package br.com.mostrai.player.playlist

/**
 * Decide, a cada playlist nova (busca periódica, virada de hora ou início
 * frio), se o app deve reiniciar a exibição agora e em que índice — sem
 * tocar em `PlayerActivity`, pra poder ser testado sem Android.
 *
 * Dois casos, nesta ordem:
 * 1. Início frio ou a janela mudou: reposiciona por tempo (decisão 7.1,
 *    `PosicaoNaPlaylist`) e reinicia agora.
 * 2. Mesma janela: reancora pelo `itemProgramacaoId` do item em exibição.
 *    Se ele ainda existe na playlist nova, só atualiza o índice, sem
 *    reiniciar — o item no ar termina e vira comprovante. Se saiu,
 *    reposiciona por tempo e reinicia.
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
