package br.com.mostrai.player.config

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configuração que o backend controla, versionada.
 *
 * Tudo aqui é **opcional**. Um campo ausente significa "não mexa no que já
 * está valendo", nunca "zere" — é a mesma regra que RN-17 já estabeleceu
 * para as margens, generalizada. É isso que permite o backend evoluir a
 * config sem coordenar release do player, e que impede uma config parcial de
 * apagar um provisionamento local bom.
 *
 * A aplicação é atômica por construção: [ConfigRemotaJson.parse] devolve
 * null se o corpo inteiro não fizer sentido, então ou o objeto chega
 * completo e coerente, ou nada é escrito.
 */
data class ConfigRemota(
    val versao: Int,
    val margens: MargensOverscan? = null,
    val rotacaoTela: Int? = null,
    val horario: HorarioOperacional? = null,
    val pinPainel: String? = null,
    /** `versionCode` abaixo do qual o player se considera obsoleto. */
    val versaoMinimaBuild: Int? = null,
    val cache: PoliticaCache = PoliticaCache(),
) {
    data class PoliticaCache(
        val tetoMegabytes: Int? = null,
    )
}

/**
 * Lê o corpo de `GET /player/:dispositivoId/config`.
 *
 * Tolerante por decisão: campo desconhecido é ignorado, campo malformado é
 * tratado como ausente. O único erro fatal é o corpo não ser JSON ou não
 * trazer `configVersion` — sem versão não há como saber se a config é mais
 * nova que a aplicada, e aplicar às cegas quebraria o mecanismo inteiro.
 */
object ConfigRemotaJson {

    fun parse(corpoBruto: String): ConfigRemota? = runCatching {
        val json = JSONObject(corpoBruto)
        val versao = if (json.has("configVersion") && !json.isNull("configVersion")) {
            json.optInt("configVersion", -1).takeIf { it >= 0 } ?: return null
        } else {
            return null
        }

        ConfigRemota(
            versao = versao,
            margens = json.optJSONObject("margens")?.let(::margens),
            rotacaoTela = json.inteiroOuNulo("rotacaoTela")?.takeIf { it in ConfigAparelho.ROTACOES_VALIDAS },
            horario = json.optJSONObject("operacao")?.let(::horario),
            pinPainel = json.textoOuNulo("pinPainel")?.takeIf { ConfigAparelho.ehPinValido(it) },
            versaoMinimaBuild = json.inteiroOuNulo("versaoMinimaBuild"),
            cache = ConfigRemota.PoliticaCache(
                tetoMegabytes = json.optJSONObject("cache")?.inteiroOuNulo("tetoMegabytes"),
            ),
        )
    }.getOrNull()

    private fun margens(json: JSONObject) = MargensOverscan(
        topo = json.floatOuZero("superior"),
        base = json.floatOuZero("inferior"),
        esquerda = json.floatOuZero("esquerda"),
        direita = json.floatOuZero("direita"),
    )

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
            regime = HorarioOperacional.regimeDeTexto(json.textoOuNulo("regime")),
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
        // Havia faixas, e nenhuma pôde ser lida: é dado ruim, não "fechado".
        // Lista vazia apagaria a tela o dia inteiro em toda tela com esta
        // config; o contrato (§7) manda acender quando o horário não é
        // utilizável (BUG-021). Fechado continua sendo a lista vazia explícita.
        if (lista.isEmpty() && array.length() > 0) return listOf(FaixaHoraria(0, 24 * 60))
        return lista
    }

    private fun JSONObject.textoOuNulo(chave: String): String? =
        if (has(chave) && !isNull(chave)) getString(chave).ifBlank { null } else null

    private fun JSONObject.inteiroOuNulo(chave: String): Int? =
        if (has(chave) && !isNull(chave)) optInt(chave, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null

    private fun JSONObject.floatOuZero(chave: String): Float =
        optDouble(chave, 0.0).toFloat().takeUnless { it.isNaN() } ?: 0f
}
