package br.com.mostrai.player.network

import br.com.mostrai.player.estado.EstadoPlayer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Heartbeat exatamente como o contrato §5 — sem campos aposentados. */
class HeartbeatJsonTest {

    private val corpo = HeartbeatJson.Corpo(
        estado = EstadoPlayer.PLAYING,
        configVersionAplicada = 7,
        criativoId = "123",
        erro = null,
        filaPendentes = 0,
        filaMaisAntigoEm = null,
    )

    private fun json(dados: HeartbeatJson.Corpo) = JSONObject(HeartbeatJson.corpo(dados))

    @Test
    fun `corpo tem so os cinco campos do contrato`() {
        val json = json(corpo)

        assertEquals(
            setOf("estado", "configVersionAplicada", "criativoId", "erro", "fila"),
            json.keys().asSequence().toSet(),
        )
        assertEquals("PLAYING", json.getString("estado"))
        assertEquals(7, json.getInt("configVersionAplicada"))
        assertEquals("123", json.getString("criativoId"))
    }

    @Test
    fun `sem criativo no ar o campo fica ausente`() {
        assertFalse(json(corpo.copy(criativoId = null)).has("criativoId"))
    }

    @Test
    fun `sem erro o campo vai nulo, o que limpa o erro anterior no servidor`() {
        val json = json(corpo)
        assertTrue(json.has("erro"))
        assertTrue(json.isNull("erro"))
    }

    @Test
    fun `com erro vai codigo, mensagem e ocorreuEm`() {
        val erro = json(
            corpo.copy(erro = HeartbeatJson.Erro("PLAYBACK_FALHOU", "codec", "2026-09-26T14:00:00-03:00")),
        ).getJSONObject("erro")

        assertEquals("PLAYBACK_FALHOU", erro.getString("codigo"))
        assertEquals("codec", erro.getString("mensagem"))
        assertEquals("2026-09-26T14:00:00-03:00", erro.getString("ocorreuEm"))
    }

    @Test
    fun `fila vai sempre, com pendentes e maisAntigoEm`() {
        val fila = json(corpo.copy(filaPendentes = 3, filaMaisAntigoEm = "2026-09-26T10:00:00-03:00"))
            .getJSONObject("fila")

        assertEquals(3, fila.getInt("pendentes"))
        assertEquals("2026-09-26T10:00:00-03:00", fila.getString("maisAntigoEm"))
        assertTrue(json(corpo).getJSONObject("fila").isNull("maisAntigoEm"))
    }

    @Test
    fun `os estados sao exatamente os nove do contrato`() {
        assertEquals(
            setOf(
                "PLAYING", "IDLE", "OUT_OF_SCHEDULE", "NO_PLAYLIST", "DOWNLOAD_ERROR",
                "PLAYBACK_ERROR", "AUTH_ERROR", "NOT_PROVISIONED", "CONFIG_ERROR",
            ),
            EstadoPlayer.values().map { it.name }.toSet(),
        )
    }

    @Test
    fun `le a resposta do contrato`() {
        val resposta = HeartbeatJson.parseResposta("""{"configVersion": 7, "playlist": {"atualizar": true}}""")!!

        assertEquals(7, resposta.configVersion)
        assertTrue(resposta.atualizarPlaylist)
    }

    @Test
    fun `resposta sem playlist nao pede busca`() {
        val resposta = HeartbeatJson.parseResposta("""{"configVersion": 7}""")!!
        assertFalse(resposta.atualizarPlaylist)
    }

    @Test
    fun `resposta malformada nao lanca e nao se passa por resposta vazia`() {
        assertNull(HeartbeatJson.parseResposta("não é json"))
    }

    @Test
    fun `corpo vazio continua valendo como nada a fazer`() {
        val resposta = HeartbeatJson.parseResposta("")!!
        assertNull(resposta.configVersion)
        assertFalse(resposta.atualizarPlaylist)
    }
}
