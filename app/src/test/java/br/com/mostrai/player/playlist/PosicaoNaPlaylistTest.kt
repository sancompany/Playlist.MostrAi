package br.com.mostrai.player.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifica a regra fechada com o GPT (item 7.1): reentrada é sempre por
 * posição temporal, item pego no meio é pulado inteiro, sem seek.
 */
class PosicaoNaPlaylistTest {

    private fun item(duracaoSegundos: Int) = ItemPlaylist(
        itemProgramacaoId = null,
        criativoId = null,
        duracaoSegundos = duracaoSegundos,
        url = "https://exemplo/video.mp4",
        anuncianteId = "anunciante",
        autoanuncio = false,
        institucional = false,
        contabiliza = true,
    )

    // Três itens de 10s: [0-10) [10-20) [20-30)
    private val itens = listOf(item(10), item(10), item(10))

    @Test
    fun `lista vazia sempre devolve zero`() {
        assertEquals(0, PosicaoNaPlaylist.calcular(emptyList(), 5_000))
    }

    @Test
    fun `decorrido negativo cai no primeiro item`() {
        assertEquals(0, PosicaoNaPlaylist.calcular(itens, -100))
    }

    @Test
    fun `bem no inicio do primeiro item`() {
        assertEquals(0, PosicaoNaPlaylist.calcular(itens, 0))
    }

    @Test
    fun `meio do primeiro item pula para o segundo, nunca faz seek`() {
        assertEquals(1, PosicaoNaPlaylist.calcular(itens, 5_000))
    }

    @Test
    fun `bem no inicio do segundo item toca ele inteiro`() {
        assertEquals(1, PosicaoNaPlaylist.calcular(itens, 10_000))
    }

    @Test
    fun `dentro da tolerancia de 500ms ainda toca o item do zero`() {
        assertEquals(1, PosicaoNaPlaylist.calcular(itens, 10_300))
    }

    @Test
    fun `passada a tolerancia conta como meio do item`() {
        assertEquals(2, PosicaoNaPlaylist.calcular(itens, 10_800))
    }

    @Test
    fun `meio do ultimo item fica no ultimo indice, nao estoura a lista`() {
        assertEquals(2, PosicaoNaPlaylist.calcular(itens, 25_000))
    }

    @Test
    fun `passou do fim conhecido da janela fica no ultimo item`() {
        assertEquals(2, PosicaoNaPlaylist.calcular(itens, 999_000))
    }
}
