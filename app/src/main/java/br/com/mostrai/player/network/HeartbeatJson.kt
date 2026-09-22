package br.com.mostrai.player.network

import br.com.mostrai.player.config.MargensOverscan
import org.json.JSONObject

/**
 * Lê `{ok, margens: {superior, direita, inferior, esquerda}}` da resposta de
 * `POST /player/:dispositivoId/heartbeat` (migration 069 do backend,
 * `sancompany/mostrai`) — mesmos 4 nomes de lado que o player web já usa
 * (`public/player.page.js`), em vmin, sempre em termos visuais. `superior`/
 * `inferior` viram `topo`/`base` aqui só pra bater com o nome que
 * [MargensOverscan] já tinha antes deste contrato existir — mesma margem,
 * nome já usado nos 3 caminhos de provisionamento local.
 */
object HeartbeatJson {
    fun parseMargens(corpoBruto: String): MargensOverscan? = runCatching {
        val margens = JSONObject(corpoBruto).optJSONObject("margens") ?: return null
        MargensOverscan(
            topo = margens.optDouble("superior", 0.0).toFloat(),
            base = margens.optDouble("inferior", 0.0).toFloat(),
            esquerda = margens.optDouble("esquerda", 0.0).toFloat(),
            direita = margens.optDouble("direita", 0.0).toFloat(),
        )
    }.getOrNull()
}
