package br.com.mostrai.player.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobre o bug encontrado na revisão desta sessão: em modo degradado
 * (contrato antigo), `itemProgramacaoId` e `janelaInicio` nunca existem, e a
 * versão anterior desta decisão (inline em `PlayerActivity.atualizarPlaylist`)
 * caía sempre no `else` de reancoragem por id — que falhava (id nulo) e
 * reiniciava do item 0 a cada busca periódica (15 em 15 min), cortando a
 * exibição em andamento sem `STATE_ENDED`.
 */
class ReposicionamentoPlaylistTest {

    private fun itemNovoContrato(id: String, duracaoSegundos: Int = 10) = ItemPlaylist(
        itemProgramacaoId = id,
        criativoId = "criativo-$id",
        duracaoSegundos = duracaoSegundos,
        url = "https://exemplo/$id.mp4",
        anuncianteId = "anunciante",
        autoanuncio = false,
        institucional = false,
        contabiliza = true,
    )

    private fun itemLegado(duracaoSegundos: Int = 10) = ItemPlaylist(
        itemProgramacaoId = null,
        criativoId = null,
        duracaoSegundos = duracaoSegundos,
        url = "https://exemplo/legado.mp4",
        anuncianteId = "anunciante",
        autoanuncio = false,
        institucional = false,
        contabiliza = true,
    )

    private fun playlistNovoContrato(vararg ids: String, janelaId: String = "janela-1") = Playlist(
        versaoContrato = 1,
        janelaId = janelaId,
        janelaInicio = "2026-09-21T10:00:00-03:00",
        janelaFim = "2026-09-21T11:00:00-03:00",
        servidorAgora = "2026-09-21T10:05:00-03:00",
        itens = ids.map { itemNovoContrato(it) },
    )

    private fun playlistLegada(quantidade: Int) = Playlist(
        versaoContrato = null,
        janelaId = null,
        janelaInicio = null,
        janelaFim = null,
        servidorAgora = null,
        itens = List(quantidade) { itemLegado() },
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
            playlistAnterior = Playlist.somenteInstitucional(),
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

    // --- O bug: modo degradado não pode reiniciar a cada poll periódico ---

    @Test
    fun `modo degradado mantem o indice atual sem reiniciar, mesmo sem id pra reancorar`() {
        val decisao = ReposicionamentoPlaylist.decidir(
            playlistAnterior = playlistLegada(5),
            indiceAnterior = 3,
            playlistNova = playlistLegada(5),
            forcarReposicionamento = false,
            trocouDeJanela = false, // legado: janelaId é sempre null, nunca "muda"
            indiceInicialPorTempo = { 0 }, // se isto fosse chamado, o teste falharia abaixo
        )
        assertEquals(3, decisao.indice)
        assertFalse(decisao.reiniciarAgora)
    }

    @Test
    fun `modo degradado clampa o indice se a playlist nova encolheu`() {
        val decisao = ReposicionamentoPlaylist.decidir(
            playlistAnterior = playlistLegada(5),
            indiceAnterior = 4,
            playlistNova = playlistLegada(2),
            forcarReposicionamento = false,
            trocouDeJanela = false,
            indiceInicialPorTempo = { 0 },
        )
        assertEquals(1, decisao.indice) // lastIndex da lista de 2
        assertFalse(decisao.reiniciarAgora)
    }

    @Test
    fun `modo degradado ainda reposiciona por tempo no inicio frio`() {
        val decisao = ReposicionamentoPlaylist.decidir(
            playlistAnterior = Playlist.somenteInstitucional(),
            indiceAnterior = 0,
            playlistNova = playlistLegada(3),
            forcarReposicionamento = true,
            trocouDeJanela = false,
            indiceInicialPorTempo = { 0 },
        )
        assertEquals(0, decisao.indice)
        assertTrue(decisao.reiniciarAgora)
    }
}
