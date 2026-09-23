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

    companion object {
        /** `"08:30"` → 510. Devolve null para qualquer coisa que não seja HH:MM válido. */
        fun deTexto(texto: String): Int? {
            val partes = texto.trim().split(":")
            if (partes.size != 2) return null
            val hora = partes[0].toIntOrNull() ?: return null
            val minuto = partes[1].toIntOrNull() ?: return null
            if (hora !in 0..24 || minuto !in 0..59) return null
            return hora * 60 + minuto
        }
    }
}

enum class RegimeOperacao {
    /** Segue o horário do ponto, entregue pelo backend nas mesmas faixas. */
    FOLLOW_POINT,

    /** Nunca apaga. É o comportamento do player desde sempre, e o padrão. */
    HORAS_24,

    /** Faixas próprias desta tela. */
    CUSTOM,
}

/**
 * Quando esta tela deve estar exibindo publicidade.
 *
 * Precisa viver **inteiro no aparelho**: uma loja sem internet continua
 * abrindo e fechando no horário de sempre, e o player não pode depender de
 * perguntar ao servidor que horas são para decidir se acende.
 *
 * **Padrão deliberadamente permissivo.** Sem dados de horário — regime não
 * configurado, faixas vazias, timezone inválida — o resultado é sempre
 * "está dentro". Uma tela acesa fora de hora é um desperdício; uma tela
 * apagada em horário comercial por causa de config faltando é receita
 * perdida e uma reclamação do dono do ponto. Na dúvida, acende.
 */
data class HorarioOperacional(
    val regime: RegimeOperacao = RegimeOperacao.HORAS_24,
    val timezone: String = TIMEZONE_PADRAO,
    val porDiaDaSemana: Map<DayOfWeek, List<FaixaHoraria>> = emptyMap(),
    /** Data → faixas naquele dia. Lista vazia significa fechado o dia inteiro. */
    val feriados: Map<LocalDate, List<FaixaHoraria>> = emptyMap(),
) {

    fun estaDentro(instante: Instant): Boolean {
        if (regime == RegimeOperacao.HORAS_24) return true
        if (porDiaDaSemana.isEmpty() && feriados.isEmpty()) return true

        val zona = zonaOuPadrao()
        val local = instante.atZone(zona)
        val data = local.toLocalDate()
        val minutoDoDia = local.hour * 60 + local.minute

        feriados[data]?.let { faixasDoFeriado ->
            return faixasDoFeriado.any { it.contem(minutoDoDia) }
        }

        val faixas = porDiaDaSemana[data.dayOfWeek] ?: return false
        return faixas.any { it.contem(minutoDoDia) }
    }

    fun zonaOuPadrao(): ZoneId =
        runCatching { ZoneId.of(timezone) }.getOrElse { ZoneId.of(TIMEZONE_PADRAO) }

    companion object {
        const val TIMEZONE_PADRAO = "America/Sao_Paulo"

        /** Aceita `seg|mon|1` … — o backend ainda não fixou a grafia. */
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

        fun regimeDeTexto(texto: String?): RegimeOperacao = when (texto?.trim()?.uppercase()) {
            "FOLLOW_POINT" -> RegimeOperacao.FOLLOW_POINT
            "CUSTOM" -> RegimeOperacao.CUSTOM
            else -> RegimeOperacao.HORAS_24
        }
    }
}
