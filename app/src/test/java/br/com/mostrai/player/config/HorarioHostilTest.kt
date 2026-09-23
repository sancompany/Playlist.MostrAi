package br.com.mostrai.player.config

import java.time.ZonedDateTime
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ciclo 6 — horário operacional com dados que não seguem o formato.
 *
 * Contrato §7: config ruim acende a tela. Tela apagada em horário comercial
 * por causa de dado mal formatado é receita perdida em todas as telas que
 * receberam aquela config.
 */
class HorarioHostilTest {

    private val zona = ZoneId.of("America/Sao_Paulo")
    private fun terca(hora: Int) = ZonedDateTime.of(2026, 9, 22, hora, 0, 0, 0, zona).toInstant()

    private fun horario(inicio: String, fim: String): HorarioOperacional = ConfigRemotaJson.parse(
        """
        {"configVersion": 1, "operacao": {"regime": "CUSTOM",
          "porDiaDaSemana": {"ter": [{"inicio": "$inicio", "fim": "$fim"}]}}}
        """.trimIndent()
    )!!.horario!!

    @Test
    fun `horario com segundos, como o time do Postgres serializa, e entendido`() {
        val h = horario("09:00:00", "18:00:00")

        assertTrue(h.estaDentro(terca(10)))
        assertFalse(h.estaDentro(terca(20)))
    }

    @Test
    fun `dia so com faixas ilegiveis acende em vez de apagar o dia inteiro`() {
        val h = horario("9h", "18h")

        assertTrue(h.estaDentro(terca(10)))
        assertTrue(h.estaDentro(terca(3)))
    }

    @Test
    fun `lista vazia continua sendo fechado o dia inteiro`() {
        // Esse é o jeito explícito de dizer "fechado" (§7) e não pode mudar.
        val h = ConfigRemotaJson.parse(
            """{"configVersion": 1, "operacao": {"regime": "CUSTOM", "porDiaDaSemana": {"ter": []}}}""",
        )!!.horario!!

        assertFalse(h.estaDentro(terca(10)))
    }
}
