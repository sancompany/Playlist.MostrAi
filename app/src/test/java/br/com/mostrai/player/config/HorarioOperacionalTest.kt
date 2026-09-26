package br.com.mostrai.player.config

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorarioOperacionalTest {

    private val saoPaulo = ZoneId.of("America/Sao_Paulo")

    private fun instante(data: String, hora: String) =
        LocalDateTime.parse("${data}T$hora").atZone(saoPaulo).toInstant()

    private fun faixa(inicio: String, fim: String) =
        FaixaHoraria(FaixaHoraria.deTexto(inicio)!!, FaixaHoraria.deTexto(fim)!!)

    @Test
    fun `ponto 24h, 00h as 24h todo dia, esta sempre dentro`() {
        val diaInteiro = listOf(FaixaHoraria(0, 24 * 60))
        val horario = HorarioOperacional(porDiaDaSemana = DayOfWeek.values().associateWith { diaInteiro })
        assertTrue(horario.estaDentro(instante("2026-09-22", "00:00")))
        assertTrue(horario.estaDentro(instante("2026-09-26", "23:59")))
    }

    @Test
    fun `faixa do dia e respeitada, inicio inclusivo e fim exclusivo`() {
        val horario = HorarioOperacional(
            porDiaDaSemana = mapOf(DayOfWeek.WEDNESDAY to listOf(faixa("09:00", "18:00"))),
        )

        // 2026-09-23 é uma quarta-feira.
        assertFalse(horario.estaDentro(instante("2026-09-23", "08:59")))
        assertTrue(horario.estaDentro(instante("2026-09-23", "09:00")))
        assertTrue(horario.estaDentro(instante("2026-09-23", "17:59")))
        assertFalse(horario.estaDentro(instante("2026-09-23", "18:00")))
    }

    @Test
    fun `dia sem faixa configurada fica fechado`() {
        val horario = HorarioOperacional(
            porDiaDaSemana = mapOf(DayOfWeek.MONDAY to listOf(faixa("09:00", "18:00"))),
        )

        assertFalse(horario.estaDentro(instante("2026-09-23", "12:00")))
    }

    @Test
    fun `faixa que cruza a meia-noite cobre os dois lados`() {
        val horario = HorarioOperacional(
            porDiaDaSemana = DayOfWeek.values().associateWith { listOf(faixa("22:00", "02:00")) },
        )

        assertTrue(horario.estaDentro(instante("2026-09-23", "23:30")))
        assertTrue(horario.estaDentro(instante("2026-09-23", "01:00")))
        assertFalse(horario.estaDentro(instante("2026-09-23", "12:00")))
    }

    @Test
    fun `noite de sexta que cruza a meia-noite continua no sabado, nao na sexta de madrugada`() {
        // Bar que só abre sexta à noite: "sex 22:00–02:00". A continuação é a
        // madrugada de SÁBADO. Lida contra a lista do próprio dia, a faixa
        // acendia sexta 00:00–02:00 (quinta à noite, fechado) e apagava
        // sábado 00:00–02:00 (horário pago).
        val horario = HorarioOperacional(
            porDiaDaSemana = mapOf(DayOfWeek.FRIDAY to listOf(faixa("22:00", "02:00"))),
        )

        assertTrue(horario.estaDentro(instante("2026-09-25", "23:00"))) // sexta
        assertTrue(horario.estaDentro(instante("2026-09-26", "01:00"))) // sábado de madrugada
        assertFalse(horario.estaDentro(instante("2026-09-25", "01:00"))) // sexta de madrugada
        assertFalse(horario.estaDentro(instante("2026-09-26", "03:00")))
    }


    @Test
    fun `feriado com lista vazia fecha o dia inteiro`() {
        val horario = HorarioOperacional(
            porDiaDaSemana = DayOfWeek.values().associateWith { listOf(faixa("00:00", "24:00")) },
            feriados = mapOf(LocalDate.parse("2026-09-23") to emptyList()),
        )

        assertFalse(horario.estaDentro(instante("2026-09-23", "12:00")))
        assertTrue(horario.estaDentro(instante("2026-09-24", "12:00")))
    }

    @Test
    fun `feriado com faixa propria sobrepoe o dia da semana`() {
        val horario = HorarioOperacional(
            porDiaDaSemana = DayOfWeek.values().associateWith { listOf(faixa("09:00", "18:00")) },
            feriados = mapOf(LocalDate.parse("2026-09-23") to listOf(faixa("14:00", "16:00"))),
        )

        assertFalse(horario.estaDentro(instante("2026-09-23", "10:00")))
        assertTrue(horario.estaDentro(instante("2026-09-23", "15:00")))
    }

    @Test
    fun `sem dado nenhum de horario a tela acende`() {
        // Padrão deliberadamente permissivo: tela apagada por config que não
        // chegou é receita perdida e reclamação do dono do ponto; tela acesa
        // fora de hora é só desperdício.
        val horario = HorarioOperacional()

        assertTrue(horario.estaDentro(instante("2026-09-23", "03:00")))
    }

    @Test
    fun `timezone invalida cai no padrao em vez de lancar`() {
        val horario = HorarioOperacional(
            timezone = "Nao/Existe",
            porDiaDaSemana = mapOf(DayOfWeek.WEDNESDAY to listOf(faixa("09:00", "18:00"))),
        )

        assertTrue(horario.estaDentro(instante("2026-09-23", "12:00")))
    }

    @Test
    fun `timezone muda o resultado na mesma marca de tempo`() {
        val faixas = DayOfWeek.values().associateWith { listOf(faixa("09:00", "18:00")) }
        val emSaoPaulo = HorarioOperacional("America/Sao_Paulo", faixas)
        val emTokio = HorarioOperacional("Asia/Tokyo", faixas)
        val momento = instante("2026-09-23", "10:00")

        assertTrue(emSaoPaulo.estaDentro(momento))
        assertFalse(emTokio.estaDentro(momento))
    }

    @Test
    fun `deTexto rejeita o que nao e HH MM`() {
        org.junit.Assert.assertNull(FaixaHoraria.deTexto("25:00"))
        org.junit.Assert.assertNull(FaixaHoraria.deTexto("09:61"))
        org.junit.Assert.assertNull(FaixaHoraria.deTexto("nove"))
        org.junit.Assert.assertEquals(510, FaixaHoraria.deTexto("08:30"))
    }

}
