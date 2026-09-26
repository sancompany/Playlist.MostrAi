package br.com.mostrai.player.network

import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lê o envelope de `GET /playlist/:dispositivoId` (contrato §7) — o único
 * formato que existe. Qualquer outra coisa (array solto, sem `itens`, sem
 * `janelaId`) devolve null: o Player segue com a última playlist válida.
 */
object PlaylistJson {

    fun parse(corpoBruto: String): Playlist? = runCatching {
        val json = JSONObject(corpoBruto.trim())
        val itensJson = json.optJSONArray("itens") ?: return null
        val janelaId = json.texto("janelaId") ?: return null
        Playlist(
            janelaId = janelaId,
            janelaInicio = json.texto("janelaInicio"),
            servidorAgora = json.texto("servidorAgora"),
            itens = itens(itensJson),
        )
    }.getOrNull()

    private fun itens(array: JSONArray): List<ItemPlaylist> = (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        ItemPlaylist(
            itemProgramacaoId = item.texto("itemProgramacaoId"),
            criativoId = item.texto("criativoId"),
            duracaoSegundos = item.optInt("duracaoSegundos", 0),
            url = item.texto("url"),
            contabiliza = item.optBoolean("contabiliza", false),
            contentHash = item.texto("contentHash")?.lowercase(),
        )
    }

    // `criativoId` pode vir número em JSON de outra origem: optString converte.
    private fun JSONObject.texto(chave: String): String? =
        if (has(chave) && !isNull(chave)) optString(chave).ifBlank { null } else null
}
