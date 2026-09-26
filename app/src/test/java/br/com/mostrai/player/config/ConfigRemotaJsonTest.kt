package br.com.mostrai.player.config

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `GET /config` exatamente como o contrato §6. */
class ConfigRemotaJsonTest {

    private val exemploDoContrato = """
        {
          "configVersion": 7,
          "margens": { "superior": 0, "direita": 1.5, "inferior": 0, "esquerda": 1.5 },
          "operacao": {
            "timezone": "America/Sao_Paulo",
            "porDiaDaSemana": {
              "seg": [{ "inicio": "08:00", "fim": "18:00" }],
              "ter": [{ "inicio": "08:00", "fim": "18:00" }],
              "qua": [{ "inicio": "08:00", "fim": "18:00" }],
              "qui": [{ "inicio": "08:00", "fim": "18:00" }],
              "sex": [{ "inicio": "08:00", "fim": "22:00" }],
              "sab": [{ "inicio": "18:00", "fim": "02:00" }],
              "dom": []
            },
            "feriados": { "2026-10-12": [], "2026-11-02": [] }
          },
          "pinSaida": "4821"
        }
    """.trimIndent()

    @Test
    fun `le o exemplo do contrato`() {
        val config = ConfigRemotaJson.parse(exemploDoContrato)!!

        assertEquals(7, config.versao)
        assertEquals(MargensOverscan(topo = 0f, base = 0f, esquerda = 1.5f, direita = 1.5f), config.margens)
        assertEquals("America/Sao_Paulo", config.horario.timezone)
        assertEquals(7, config.horario.porDiaDaSemana.size)
        assertEquals(emptyList<FaixaHoraria>(), config.horario.porDiaDaSemana[DayOfWeek.SUNDAY])
        assertEquals(FaixaHoraria(18 * 60, 2 * 60), config.horario.porDiaDaSemana[DayOfWeek.SATURDAY]!!.single())
        assertEquals(emptyList<FaixaHoraria>(), config.horario.feriados[LocalDate.parse("2026-10-12")])
        assertEquals("4821", config.pinSaida)
    }

    @Test
    fun `fim 24h vale ate o fim do dia`() {
        val config = ConfigRemotaJson.parse(
            """{"configVersion":1,"operacao":{"porDiaDaSemana":{"seg":[{"inicio":"00:00","fim":"24:00"}]}}}""",
        )!!
        assertEquals(FaixaHoraria(0, 24 * 60), config.horario.porDiaDaSemana[DayOfWeek.MONDAY]!!.single())
    }

    @Test
    fun `sem configVersion e invalida`() {
        assertNull(ConfigRemotaJson.parse("""{"margens":{"superior":1}}"""))
    }

    @Test
    fun `corpo malformado devolve nulo em vez de lancar`() {
        assertNull(ConfigRemotaJson.parse("<html>502</html>"))
        assertNull(ConfigRemotaJson.parse(""))
    }

    @Test
    fun `pinSaida nulo significa sem saida autorizada`() {
        assertNull(ConfigRemotaJson.parse("""{"configVersion":1,"pinSaida":null}""")!!.pinSaida)
    }

    @Test
    fun `pinSaida aceita de 4 a 8 digitos e nada mais`() {
        fun pin(valor: String) = ConfigRemotaJson.parse("""{"configVersion":1,"pinSaida":"$valor"}""")!!.pinSaida
        assertEquals("4821", pin("4821"))
        assertEquals("48213579", pin("48213579"))
        assertNull(pin("482"))
        assertNull(pin("482135790"))
        assertNull(pin("48a1"))
    }

    @Test
    fun `margem fica entre 0 e 10 vmin`() {
        val config = ConfigRemotaJson.parse(
            """{"configVersion":1,"margens":{"superior":-3,"direita":99,"inferior":"x","esquerda":10}}""",
        )!!
        assertEquals(MargensOverscan(topo = 0f, base = 0f, esquerda = 10f, direita = 10f), config.margens)
    }

    @Test
    fun `config sem operacao acende o dia inteiro`() {
        val config = ConfigRemotaJson.parse("""{"configVersion":1}""")!!
        assertTrue(config.horario.estaDentro(java.time.Instant.parse("2026-09-27T03:00:00Z")))
    }

    @Test
    fun `faixa com horario invalido e descartada sem derrubar o resto`() {
        val config = ConfigRemotaJson.parse(
            """{"configVersion":1,"operacao":{"porDiaDaSemana":{
               "seg":[{"inicio":"25:00","fim":"26:00"},{"inicio":"09:00","fim":"10:00"}]}}}""",
        )!!
        assertEquals(listOf(FaixaHoraria(9 * 60, 10 * 60)), config.horario.porDiaDaSemana[DayOfWeek.MONDAY])
    }
}
