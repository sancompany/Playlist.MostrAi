package br.com.mostrai.player.network

import br.com.mostrai.player.proof.EventoExibicao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class PlayedJsonTest {

    private fun evento(id: String) = EventoExibicao(
        execucaoId = id,
        janelaId = "janela-1",
        itemProgramacaoId = "slot-1",
        criativoId = "crv-1",
        anuncianteId = "anun-1",
        formatoLegado = false,
        iniciadoEm = "2026-09-21T13:00:00-03:00",
        terminadoEm = "2026-09-21T13:00:20-03:00",
        tentativas = 0,
        proximoEnvioElegivelEm = 0,
        criadoEmMs = 0,
    )

    @Test
    fun `corpoLote embrulha em eventos, mais antigo primeiro na ordem dada`() {
        val corpo = PlayedJson.corpoLote(listOf(evento("e1"), evento("e2")))
        val json = JSONObject(corpo)
        val eventos = json.getJSONArray("eventos")
        assertEquals(2, eventos.length())
        assertEquals("e1", eventos.getJSONObject(0).getString("execucaoId"))
        assertEquals("janela-1", eventos.getJSONObject(0).getString("janelaId"))
    }

    @Test
    fun `parseResultados le status por execucaoId`() {
        val corpo = """
            {"resultados": [
                {"execucaoId": "e1", "status": "contabilizado"},
                {"execucaoId": "e2", "status": "duplicado"}
            ]}
        """.trimIndent()

        val resultados = PlayedJson.parseResultados(corpo)

        assertEquals("contabilizado", resultados["e1"])
        assertEquals("duplicado", resultados["e2"])
    }

    @Test
    fun `um resultado malformado nao descarta os outros`() {
        // Descartar a resposta inteira deixava na fila comprovantes que o
        // servidor JÁ contou; reenviados até expirar, viravam "perda".
        val corpo = """{"resultados": [null, 7, {"execucaoId": "e1", "status": "contabilizado"}]}"""

        assertEquals(mapOf("e1" to "contabilizado"), PlayedJson.parseResultados(corpo))
    }

    @Test
    fun `corpoLegado manda so anuncianteId`() {
        val corpo = PlayedJson.corpoLegado("anun-1")
        assertEquals("anun-1", JSONObject(corpo).getString("anuncianteId"))
    }

    @Test
    fun `parseContouLegado le contou explicito`() {
        assertEquals(false, PlayedJson.parseContouLegado("""{"ok":true,"contou":false}"""))
        assertEquals(true, PlayedJson.parseContouLegado("""{"ok":true,"contou":true}"""))
    }

    @Test
    fun `parseContouLegado sem contou assume que creditou`() {
        assertTrue(PlayedJson.parseContouLegado("""{"ok":true}"""))
    }
}
