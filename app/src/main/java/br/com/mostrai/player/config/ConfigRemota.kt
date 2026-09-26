package br.com.mostrai.player.config

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * Corpo de `GET /player/:dispositivoId/config` (contrato §6): margens da
 * tela, horário do ponto e o PIN de saída global. Nada além disso —
 * orientação, servidor e intervalos são fixos no APK ([br.com.mostrai.player.Produto]).
 *
 * A config é um retrato inteiro, lido de uma vez no servidor: aplicada ou
 * não, nunca campo a campo.
 */
data class ConfigRemota(
    val versao: Int,
    val margens: MargensOverscan = MargensOverscan(),
    val horario: HorarioOperacional = HorarioOperacional(),
    /** 4 a 8 dígitos. `null` = nenhum PIN definido: a saída autorizada fica indisponível. */
    val pinSaida: String? = null,
)

/**
 * Tolerante por decisão: campo com tipo errado vale o padrão. O único erro
 * fatal é o corpo não ser JSON ou não trazer `configVersion` — sem versão
 * não há como confirmar ao servidor o que foi aplicado.
 */
object ConfigRemotaJson {

    /** Margem máxima por lado, em vmin (contrato §6). */
    const val TETO_MARGEM = 10f

    private val FORMATO_PIN = Regex("^\\d{4,8}$")

    fun parse(corpoBruto: String): ConfigRemota? = runCatching {
        val json = JSONObject(corpoBruto)
        val versao = json.inteiroOuNulo("configVersion")?.takeIf { it >= 0 } ?: return null
        ConfigRemota(
            versao = versao,
            margens = json.optJSONObject("margens")?.let(::margens) ?: MargensOverscan(),
            horario = json.optJSONObject("operacao")?.let(::horario) ?: HorarioOperacional(),
            pinSaida = json.textoOuNulo("pinSaida")?.takeIf { FORMATO_PIN.matches(it) },
        )
    }.getOrNull()

    private fun margens(json: JSONObject) = MargensOverscan(
        topo = json.margem("superior"),
        base = json.margem("inferior"),
        esquerda = json.margem("esquerda"),
        direita = json.margem("direita"),
    )

    private fun JSONObject.margem(chave: String): Float =
        (optDouble(chave, 0.0).toFloat().takeUnless { it.isNaN() } ?: 0f).coerceIn(0f, TETO_MARGEM)

    private fun horario(json: JSONObject): HorarioOperacional {
        val porDia = mutableMapOf<java.time.DayOfWeek, List<FaixaHoraria>>()
        json.optJSONObject("porDiaDaSemana")?.let { dias ->
            dias.keys().forEach { chave ->
                val dia = HorarioOperacional.diaDeTexto(chave) ?: return@forEach
                porDia[dia] = faixas(dias.optJSONArray(chave))
            }
        }

        val feriados = mutableMapOf<LocalDate, List<FaixaHoraria>>()
        json.optJSONObject("feriados")?.let { mapa ->
            mapa.keys().forEach { chave ->
                val data = runCatching { LocalDate.parse(chave) }.getOrNull() ?: return@forEach
                feriados[data] = faixas(mapa.optJSONArray(chave))
            }
        }

        return HorarioOperacional(
            timezone = json.textoOuNulo("timezone") ?: HorarioOperacional.TIMEZONE_PADRAO,
            porDiaDaSemana = porDia,
            feriados = feriados,
        )
    }

    private fun faixas(array: JSONArray?): List<FaixaHoraria> {
        if (array == null) return emptyList()
        val lista = mutableListOf<FaixaHoraria>()
        for (i in 0 until array.length()) {
            val faixa = array.optJSONObject(i) ?: continue
            val inicio = faixa.textoOuNulo("inicio")?.let(FaixaHoraria::deTexto) ?: continue
            val fim = faixa.textoOuNulo("fim")?.let(FaixaHoraria::deTexto) ?: continue
            lista += FaixaHoraria(inicio, fim)
        }
        // Havia faixas e nenhuma pôde ser lida: é dado ruim, não "fechado".
        // Mesma regra do backend (src/lib/operacao-tela.js#lerFaixas): na
        // dúvida, acende (BUG-021). Fechado é a lista vazia explícita.
        if (lista.isEmpty() && array.length() > 0) return listOf(FaixaHoraria(0, 24 * 60))
        return lista
    }

    private fun JSONObject.textoOuNulo(chave: String): String? =
        if (has(chave) && !isNull(chave)) optString(chave).trim().ifBlank { null } else null

    private fun JSONObject.inteiroOuNulo(chave: String): Int? =
        if (has(chave) && !isNull(chave)) optInt(chave, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null
}
