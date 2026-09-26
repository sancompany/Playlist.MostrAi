package br.com.mostrai.player.config

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Faixa de funcionamento dentro de um dia, em minutos desde a meia-noite local. */
data class FaixaHoraria(val inicioMinutos: Int, val fimMinutos: Int) {

    /**
     * `22:00–02:00` é uma faixa legítima (bar que fecha de madrugada): quando
     * o fim é menor que o início, a faixa cruza a meia-noite e o teste vira
     * uma união de dois intervalos, não uma interseção.
     */
    fun contem(minutoDoDia: Int): Boolean = if (fimMinutos >= inicioMinutos) {
        minutoDoDia >= inicioMinutos && minutoDoDia < fimMinutos
    } else {
        minutoDoDia >= inicioMinutos || minutoDoDia < fimMinutos
    }

    /** Faixa `22:00–02:00`: começa num dia e termina no seguinte. */
    val cruzaMeiaNoite: Boolean get() = fimMinutos < inicioMinutos

    /** A parte da faixa que cai no próprio dia em que ela começa. */
    fun contemNoDiaDeInicio(minutoDoDia: Int): Boolean =
        if (cruzaMeiaNoite) minutoDoDia >= inicioMinutos else minutoDoDia >= inicioMinutos && minutoDoDia < fimMinutos

    /** A parte da faixa que transborda para a madrugada do dia seguinte. */
    fun contemNaMadrugadaSeguinte(minutoDoDia: Int): Boolean = cruzaMeiaNoite && minutoDoDia < fimMinutos

    companion object {
        /** `"08:30"` → 510. Devolve null para qualquer coisa que não seja HH:MM válido. */
        fun deTexto(texto: String): Int? {
            // HH:MM, ou HH:MM:SS com os segundos ignorados — é assim que uma
            // coluna `time` do Postgres sai em JSON (BUG-021).
            val partes = texto.trim().split(":")
            if (partes.size !in 2..3) return null
            val hora = partes[0].toIntOrNull() ?: return null
            val minuto = partes[1].toIntOrNull() ?: return null
            val segundo = partes.getOrNull(2)?.let { it.toIntOrNull() ?: return null } ?: 0
            if (hora !in 0..24 || minuto !in 0..59 || segundo !in 0..59) return null
            return hora * 60 + minuto
        }
    }
}

/**
 * Quando esta tela deve estar exibindo publicidade — sempre o horário do
 * PONTO, entregue em `operacao` na config (contrato §6). Mesma semântica de
 * `deveriaOperar` no backend (`src/lib/operacao-tela.js`).
 *
 * Precisa viver **inteiro no aparelho**: uma loja sem internet continua
 * abrindo e fechando no horário de sempre, e o player não pode depender de
 * perguntar ao servidor que horas são para decidir se acende.
 *
 * **Padrão deliberadamente permissivo.** Sem dados de horário — config
 * ainda não recebida, mapa vazio — o resultado é sempre "está dentro";
 * timezone inválida cai em `America/Sao_Paulo`. Uma tela acesa fora de hora
 * é um desperdício; uma tela apagada em horário comercial por causa de
 * config faltando é receita perdida. Na dúvida, acende.
 */
data class HorarioOperacional(
    val timezone: String = TIMEZONE_PADRAO,
    val porDiaDaSemana: Map<DayOfWeek, List<FaixaHoraria>> = emptyMap(),
    /** Data → faixas naquele dia. Lista vazia significa fechado o dia inteiro. */
    val feriados: Map<LocalDate, List<FaixaHoraria>> = emptyMap(),
) {

    fun estaDentro(instante: Instant): Boolean {
        if (porDiaDaSemana.isEmpty() && feriados.isEmpty()) return true

        val zona = zonaOuPadrao()
        val local = instante.atZone(zona)
        val data = local.toLocalDate()
        val minutoDoDia = local.hour * 60 + local.minute

        // Feriado sobrepõe o dia inteiro, inclusive a madrugada — "lista
        // vazia = fechado o dia inteiro" (contrato §7).
        feriados[data]?.let { faixasDoFeriado ->
            return faixasDoFeriado.any { it.contem(minutoDoDia) }
        }

        // BUG-026: "sex 22:00–02:00" continua na madrugada de SÁBADO. Lida só
        // contra a lista do próprio dia, a faixa acendia sexta de madrugada
        // (quinta à noite) e apagava sábado de madrugada (horário pago).
        val hoje = porDiaDaSemana[data.dayOfWeek].orEmpty()
        val ontem = faixasDoDia(data.minusDays(1))
        return hoje.any { it.contemNoDiaDeInicio(minutoDoDia) } ||
            ontem.any { it.contemNaMadrugadaSeguinte(minutoDoDia) }
    }

    private fun faixasDoDia(data: LocalDate): List<FaixaHoraria> =
        feriados[data] ?: porDiaDaSemana[data.dayOfWeek].orEmpty()

    fun zonaOuPadrao(): ZoneId =
        runCatching { ZoneId.of(timezone) }.getOrElse { ZoneId.of(TIMEZONE_PADRAO) }

    companion object {
        const val TIMEZONE_PADRAO = "America/Sao_Paulo"

        /** O contrato usa `seg`…`dom`; as outras grafias são tolerância. */
        fun diaDeTexto(texto: String): DayOfWeek? = when (texto.trim().lowercase()) {
            "seg", "segunda", "mon", "monday", "1" -> DayOfWeek.MONDAY
            "ter", "terca", "terça", "tue", "tuesday", "2" -> DayOfWeek.TUESDAY
            "qua", "quarta", "wed", "wednesday", "3" -> DayOfWeek.WEDNESDAY
            "qui", "quinta", "thu", "thursday", "4" -> DayOfWeek.THURSDAY
            "sex", "sexta", "fri", "friday", "5" -> DayOfWeek.FRIDAY
            "sab", "sábado", "sabado", "sat", "saturday", "6" -> DayOfWeek.SATURDAY
            "dom", "domingo", "sun", "sunday", "7", "0" -> DayOfWeek.SUNDAY
            else -> null
        }

    }
}
