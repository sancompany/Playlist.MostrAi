package br.com.mostrai.player.network

import br.com.mostrai.player.proof.EventoExibicao
import org.json.JSONArray
import org.json.JSONObject

/** Corpo e resposta de `POST /player/:dispositivoId/played` (contrato §8). */
object PlayedJson {

    fun corpoLote(eventos: List<EventoExibicao>): String {
        val array = JSONArray()
        for (e in eventos) {
            array.put(
                JSONObject().apply {
                    put("execucaoId", e.execucaoId)
                    put("janelaId", e.janelaId ?: JSONObject.NULL)
                    put("itemProgramacaoId", e.itemProgramacaoId ?: JSONObject.NULL)
                    put("criativoId", e.criativoId ?: JSONObject.NULL)
                    put("iniciadoEm", e.iniciadoEm)
                    put("terminadoEm", e.terminadoEm ?: JSONObject.NULL)
                }
            )
        }
        return JSONObject().put("eventos", array).toString()
    }

    /**
     * `execucaoId → status`, exatamente como veio. `null` se o corpo não é o
     * do contrato — o lote fica na fila, nunca é dado como resolvido.
     */
    fun parseResultados(corpoBruto: String): Map<String, String>? = runCatching {
        val resultados = JSONObject(corpoBruto).optJSONArray("resultados") ?: return null
        val mapa = mutableMapOf<String, String>()
        for (i in 0 until resultados.length()) {
            // ROB-006: um elemento que não é objeto não invalida os outros.
            val item = resultados.optJSONObject(i) ?: continue
            val id = item.optString("execucaoId", "")
            val status = item.optString("status", "")
            if (id.isNotEmpty() && status.isNotEmpty()) mapa[id] = status
        }
        mapa
    }.getOrNull()
}
