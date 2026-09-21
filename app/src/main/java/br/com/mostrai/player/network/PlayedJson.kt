package br.com.mostrai.player.network

import br.com.mostrai.player.proof.EventoExibicao
import org.json.JSONArray
import org.json.JSONObject

/** Monta e lê os corpos de POST /played nas duas formas do contrato (seção 6.2 e 6.6). */
object PlayedJson {

    fun corpoLote(eventos: List<EventoExibicao>): String {
        val array = JSONArray()
        for (e in eventos) {
            array.put(
                JSONObject().apply {
                    put("execucaoId", e.execucaoId)
                    put("janelaId", e.janelaId)
                    put("itemProgramacaoId", e.itemProgramacaoId)
                    put("criativoId", e.criativoId)
                    put("iniciadoEm", e.iniciadoEm)
                    put("terminadoEm", e.terminadoEm)
                }
            )
        }
        return JSONObject().put("eventos", array).toString()
    }

    /** execucaoId -> status, exatamente como a resposta trouxe (seção 6.3). */
    fun parseResultados(corpoBruto: String): Map<String, String> {
        val json = JSONObject(corpoBruto)
        val resultados = json.optJSONArray("resultados") ?: JSONArray()
        val mapa = mutableMapOf<String, String>()
        for (i in 0 until resultados.length()) {
            val item = resultados.getJSONObject(i)
            val id = item.optString("execucaoId", "")
            val status = item.optString("status", "")
            if (id.isNotEmpty() && status.isNotEmpty()) mapa[id] = status
        }
        return mapa
    }

    fun corpoLegado(anuncianteId: String): String =
        JSONObject().put("anuncianteId", anuncianteId).toString()

    /** true/false conforme o corpo antigo `{ok:true, contou:...}` / `{ok:true}`. */
    fun parseContouLegado(corpoBruto: String): Boolean = runCatching {
        val json = JSONObject(corpoBruto)
        if (json.has("contou")) json.optBoolean("contou") else true
    }.getOrDefault(true)
}
