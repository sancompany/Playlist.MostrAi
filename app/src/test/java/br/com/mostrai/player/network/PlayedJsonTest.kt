package br.com.mostrai.player.network

import br.com.mostrai.player.proof.EventoExibicao
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Corpo e resposta de `POST /played` exatamente como o contrato §8. */
class PlayedJsonTest {

    private fun evento(id: String) = EventoExibicao(
        execucaoId = id,
        janelaId = "235|2026-09-26T14:00:00.000Z",
        itemProgramacaoId = "235|2026-09-26T14:00:00.000Z|0|17",
        criativoId = "88",
        iniciadoEm = "2026-09-26T14:07:31-03:00",
        terminadoEm = "2026-09-26T14:07:46-03:00",
        tentativas = 0,
        proximoEnvioElegivelEm = 0L,
        criadoEmMs = 0L,
    )

    @Test
    fun `corpoLote tem exatamente os campos do contrato, na ordem dada`() {
        val json = JSONObject(PlayedJson.corpoLote(listOf(evento("a"), evento("b"))))

        val eventos = json.getJSONArray("eventos")
        assertEquals(2, eventos.length())
        assertEquals("a", eventos.getJSONObject(0).getString("execucaoId"))
        val primeiro = eventos.getJSONObject(0)
        assertEquals(
            setOf("execucaoId", "janelaId", "itemProgramacaoId", "criativoId", "iniciadoEm", "terminadoEm"),
            primeiro.keys().asSequence().toSet(),
        )
        assertEquals("235|2026-09-26T14:00:00.000Z|0|17", primeiro.getString("itemProgramacaoId"))
        assertEquals(setOf("eventos"), json.keys().asSequence().toSet())
    }

    @Test
    fun `parseResultados le status por execucaoId`() {
        val corpo = """{"resultados":[{"execucaoId":"a","status":"contabilizado"},
            {"execucaoId":"b","status":"duplicado"}]}"""

        assertEquals(mapOf("a" to "contabilizado", "b" to "duplicado"), PlayedJson.parseResultados(corpo))
    }

    @Test
    fun `um resultado malformado nao descarta os outros`() {
        val corpo = """{"resultados":[42,{"execucaoId":"a","status":"teto_atingido"}]}"""

        assertEquals(mapOf("a" to "teto_atingido"), PlayedJson.parseResultados(corpo))
    }

    @Test
    fun `corpo fora do contrato nao se passa por lote resolvido`() {
        assertNull(PlayedJson.parseResultados("<html>erro</html>"))
        assertNull(PlayedJson.parseResultados("""{"ok":true}"""))
    }
}
