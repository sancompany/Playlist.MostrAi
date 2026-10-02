package br.com.mostrai.player.network

import br.com.mostrai.player.operacao.RegistroOperacional
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Corpo de `POST /player/:dispositivoId/operacao` — as sessões operacionais
 * locais ([RegistroOperacional]). Fatos, nunca conclusões: o servidor decide
 * o que é tempo válido e a que local ele pertence.
 */
object OperacaoJson {

    fun corpo(sessoes: List<RegistroOperacional.Sessao>): String = JSONObject().put(
        "sessoes",
        JSONArray().apply {
            sessoes.forEach { s ->
                put(
                    JSONObject()
                        .put("sessaoId", s.sessaoId)
                        .put("bootCount", s.bootCount)
                        .put("inicioUptimeMs", s.inicioUptimeMs)
                        .put("fimUptimeMs", s.fimUptimeMs)
                        .put("duracaoMs", s.duracaoMs)
                        .put("inicioEm", iso(s.inicioParedeMs))
                        .put("fimEm", iso(s.fimParedeMs))
                        .put("inicioServidorEm", s.inicioServidorMs?.let(::iso) ?: JSONObject.NULL)
                        .put("fimServidorEm", s.fimServidorMs?.let(::iso) ?: JSONObject.NULL)
                        .put("encerrada", !s.aberta)
                        .put("motivo", s.motivoFim ?: JSONObject.NULL),
                )
            }
        },
    ).toString()

    /** `sessaoId` das sessões que o servidor registrou (ou recusou de vez). Null = resposta fora do contrato. */
    fun parseConfirmadas(corpoBruto: String): Set<String>? = runCatching {
        val resultados = JSONObject(corpoBruto).getJSONArray("resultados")
        (0 until resultados.length()).mapNotNullTo(mutableSetOf()) { i ->
            val r = resultados.optJSONObject(i) ?: return@mapNotNullTo null
            val status = r.optString("status")
            r.optString("sessaoId").ifBlank { null }?.takeIf { status in STATUS_FINAIS }
        }
    }.getOrNull()

    /** `registrada` (gravada ou já existia) e `invalida` (nunca vai ser aceita): os dois encerram o reenvio. */
    val STATUS_FINAIS = setOf("registrada", "invalida")

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()
}
