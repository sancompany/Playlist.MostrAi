package br.com.mostrai.player.network

import br.com.mostrai.player.operacao.RegistroOperacional
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Corpo de `POST /player/:dispositivoId/operacao` (contrato §8.5):
 * `{segmentos: [{bootId, seq, inicio, fim}]}`, `inicio`/`fim` no relógio do
 * servidor (uptime + âncora do mesmo boot). Fatos, nunca conclusões: o
 * servidor decide o que é tempo válido e a que local ele pertence.
 */
object OperacaoJson {

    /** Só segmentos com âncora chegam aqui ([RegistroOperacional.pendentes]). */
    fun corpo(segmentos: List<RegistroOperacional.Segmento>): String = JSONObject().put(
        "segmentos",
        JSONArray().apply {
            segmentos.forEach { s ->
                val inicio = s.inicioServidorMs ?: return@forEach
                val fim = s.fimServidorMs ?: return@forEach
                put(
                    JSONObject()
                        .put("bootId", s.bootId)
                        .put("seq", s.seq)
                        .put("inicio", Instant.ofEpochMilli(inicio).toString())
                        .put("fim", Instant.ofEpochMilli(fim).toString()),
                )
            }
        },
    ).toString()

    /**
     * `{resultados: [{bootId, seq, status}]}` → status por `(bootId, seq)`.
     * Null = resposta fora do contrato (o lote fica para a próxima).
     */
    fun parseResultados(corpoBruto: String): Map<Pair<String, Int>, String>? = runCatching {
        val resultados = JSONObject(corpoBruto).getJSONArray("resultados")
        buildMap {
            for (i in 0 until resultados.length()) {
                val r = resultados.optJSONObject(i) ?: continue
                val boot = r.optString("bootId").ifBlank { null } ?: continue
                if (!r.has("seq") || r.isNull("seq")) continue
                val seq = r.optInt("seq", -1).takeIf { it >= 0 } ?: continue
                val status = r.optString("status").ifBlank { null } ?: continue
                put(boot to seq, status)
            }
        }
    }.getOrNull()
}
