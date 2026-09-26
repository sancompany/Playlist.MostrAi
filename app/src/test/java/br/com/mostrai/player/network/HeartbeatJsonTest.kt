package br.com.mostrai.player.network

import br.com.mostrai.player.config.MargensOverscan
import br.com.mostrai.player.estado.EstadoPlayer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatJsonTest {

    private fun corpoBase(
        estado: EstadoPlayer = EstadoPlayer.PLAYING,
        erroCodigo: String? = null,
        filaPendentes: Int = 0,
    ) = HeartbeatJson.Corpo(
        estado = estado,
        configVersionAplicada = 183,
        criativoId = "criativo-1",
        ultimaPlaylistOkEm = "2026-09-23T10:00:00-03:00",
        filaPendentes = filaPendentes,
        filaMaisAntigoEm = null,
        erroCodigo = erroCodigo,
        erroEm = "2026-09-23T03:00:00-03:00",
        erroMensagem = "detalhe",
        desvioRelogioMs = -4200,
    )

    // ------------------------------------------------------------------ corpo

    @Test
    fun `corpo carrega o que o admin precisa para derivar os estados`() {
        val json = JSONObject(HeartbeatJson.corpo(corpoBase()))

        assertEquals(2, json.getInt("versaoContrato"))
        assertEquals("PLAYING", json.getString("estado"))
        assertEquals(183, json.getInt("configVersionAplicada"))
        assertEquals("criativo-1", json.getString("criativoId"))
        assertEquals("2026-09-23T10:00:00-03:00", json.getString("ultimaPlaylistOkEm"))
        assertEquals(-4200L, json.getLong("desvioRelogioMs"))
    }

    @Test
    fun `fila vai sempre, mesmo vazia`() {
        val fila = JSONObject(HeartbeatJson.corpo(corpoBase(filaPendentes = 412))).getJSONObject("fila")

        assertEquals(412, fila.getInt("pendentes"))
        assertTrue(fila.isNull("maisAntigoEm"))
    }

    @Test
    fun `sem erro o campo vai nulo, nao omitido`() {
        // Omitir faria o backend não conseguir distinguir "não houve erro" de
        // "esta versão do player não reporta erro".
        val json = JSONObject(HeartbeatJson.corpo(corpoBase()))

        assertTrue(json.has("erro"))
        assertTrue(json.isNull("erro"))
    }

    @Test
    fun `com erro vai codigo, momento e mensagem`() {
        val erro = JSONObject(HeartbeatJson.corpo(corpoBase(erroCodigo = "PLAYBACK_FALHOU")))
            .getJSONObject("erro")

        assertEquals("PLAYBACK_FALHOU", erro.getString("codigo"))
        assertEquals("2026-09-23T03:00:00-03:00", erro.getString("ocorreuEm"))
        assertEquals("detalhe", erro.getString("mensagem"))
    }

    @Test
    fun `estado fora do horario aparece como tal`() {
        val json = JSONObject(HeartbeatJson.corpo(corpoBase(estado = EstadoPlayer.OUT_OF_SCHEDULE)))

        assertEquals("OUT_OF_SCHEDULE", json.getString("estado"))
    }

    // --------------------------------------------------------------- resposta

    @Test
    fun `le a resposta V2 completa`() {
        val corpo = """
            {
              "servidorAgora": "2026-09-23T13:00:00Z",
              "configVersion": 184,
              "playlist": {"atualizar": true},
              "novaChave": "chave-nova"
            }
        """.trimIndent()

        val resposta = HeartbeatJson.parseResposta(corpo)!!

        assertEquals("2026-09-23T13:00:00Z", resposta.servidorAgora)
        assertEquals(184, resposta.configVersion)
        assertTrue(resposta.atualizarPlaylist)
        assertEquals("chave-nova", resposta.novaChave)
    }

    @Test
    fun `resposta V1 com margens continua sendo entendida`() {
        val corpo = """{"ok":true,"margens":{"superior":3,"direita":1.5,"inferior":0,"esquerda":2}}"""

        val resposta = HeartbeatJson.parseResposta(corpo)!!

        assertEquals(MargensOverscan(3f, 0f, 2f, 1.5f), resposta.margens)
        assertNull(resposta.configVersion)
        assertFalse(resposta.atualizarPlaylist)
    }

    @Test
    fun `resposta vazia nao pede nada`() {
        val resposta = HeartbeatJson.parseResposta("""{"ok":true}""")!!

        assertNull(resposta.configVersion)
        assertNull(resposta.margens)
        assertFalse(resposta.atualizarPlaylist)
    }

    @Test
    fun `resposta malformada nao lanca e nao se passa por resposta vazia`() {
        // BUG-034: tratada como {"ok": true}, ela dizia "sem atualização".
        assertNull(HeartbeatJson.parseResposta("não é json"))
    }

    @Test
    fun `corpo vazio continua valendo como nada a fazer`() {
        val resposta = HeartbeatJson.parseResposta("")!!

        assertFalse(resposta.atualizarPlaylist)
    }

    @Test
    fun `parseMargens do formato antigo continua disponivel`() {
        val margens = HeartbeatJson.parseMargens("""{"margens":{"superior":5}}""")

        assertEquals(5f, margens?.topo)
        assertEquals(0f, margens?.base)
    }

    @Test
    fun `sem margens no corpo antigo devolve nulo`() {
        assertNull(HeartbeatJson.parseMargens("""{"ok":true}"""))
    }
}
