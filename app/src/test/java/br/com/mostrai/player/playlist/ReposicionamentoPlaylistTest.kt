package br.com.mostrai.player.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decisão de índice a cada playlist nova: início frio e janela nova
 * reposicionam por tempo; mesma janela reancora pelo `itemProgramacaoId`
 * sem cortar o item no ar.
 */
class ReposicionamentoPlaylistTest {

    private fun itemNovoContrato(id: String, duracaoSegundos: Int = 10) = ItemPlaylist(
        itemProgramacaoId = id,
        criativoId = "criativo-$id",
        duracaoSegundos = duracaoSegundos,
        url = "https://exemplo/$id.mp4",
        contabiliza = true,
    )


    private fun playlistNovoContrato(vararg ids: String, janelaId: String = "janela-1") = Playlist(
        janelaId = janelaId,
        janelaInicio = "2026-09-21T10:00:00-03:00",
        servidorAgora = "2026-09-21T10:05:00-03:00",
        itens = ids.map { itemNovoContrato(it) },
    )


    private val indiceInicialFixo = { 0 }

    @Test
    fun `playlist vazia nao reinicia e fica no indice zero`() {
        val decisao = ReposicionamentoPlaylist.decidir(
            playlistAnterior = playlistNovoContrato("a", "b"),
            indiceAnterior = 1,
            playlistNova = playlistNovoContrato(),
            forcarReposicionamento = false,
            trocouDeJanela = false,
            indiceInicialPorTempo = indiceInicialFixo,
        )
        assertEquals(0, decisao.indice)
        assertFalse(decisao.reiniciarAgora)
    }

    @Test
    fun `inicio frio sempre reposiciona por tempo e reinicia`() {
        val decisao = ReposicionamentoPlaylist.decidir(
            playlistAnterior = Playlist.VAZIA,
            indiceAnterior = 0,
            playlistNova = playlistNovoContrato("a", "b", "c"),
            forcarReposicionamento = true,
            trocouDeJanela = false,
            indiceInicialPorTempo = { 2 },
        )
        assertEquals(2, decisao.indice)
        assertTrue(decisao.reiniciarAgora)
    }

    @Test
    fun `janela nova reposiciona por tempo e reinicia mesmo sem forcar`() {
        val decisao = ReposicionamentoPlaylist.decidir(
            playlistAnterior = playlistNovoContrato("a", "b", janelaId = "janela-1"),
            indiceAnterior = 1,
            playlistNova = playlistNovoContrato("c", "d", janelaId = "janela-2"),
            forcarReposicionamento = false,
            trocouDeJanela = true,
            indiceInicialPorTempo = { 0 },
        )
        assertEquals(0, decisao.indice)
        assertTrue(decisao.reiniciarAgora)
    }

    @Test
    fun `contrato novo, mesma janela, item ainda existe so reancora sem reiniciar`() {
        val decisao = ReposicionamentoPlaylist.decidir(
            playlistAnterior = playlistNovoContrato("a", "b", "c"),
            indiceAnterior = 1, // tocando "b"
            playlistNova = playlistNovoContrato("a", "b", "c", "d"), // "b" continua, só cresceu
            forcarReposicionamento = false,
            trocouDeJanela = false,
            indiceInicialPorTempo = indiceInicialFixo,
        )
        assertEquals(1, decisao.indice) // "b" continua no índice 1
        assertFalse(decisao.reiniciarAgora)
    }

    @Test
    fun `contrato novo, item saiu da elegibilidade, reposiciona por tempo e reinicia`() {
        val decisao = ReposicionamentoPlaylist.decidir(
            playlistAnterior = playlistNovoContrato("a", "b", "c"),
            indiceAnterior = 1, // tocando "b"
            playlistNova = playlistNovoContrato("a", "c"), // "b" saiu
            forcarReposicionamento = false,
            trocouDeJanela = false,
            indiceInicialPorTempo = { 1 },
        )
        assertEquals(1, decisao.indice)
        assertTrue(decisao.reiniciarAgora)
    }
}
