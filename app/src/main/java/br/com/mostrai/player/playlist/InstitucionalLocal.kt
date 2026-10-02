package br.com.mostrai.player.playlist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * O conteúdo institucional da Mostraí que fica sempre na TV — o fallback
 * quando a programação comercial vence sem internet, ou quando não há
 * programação nenhuma (Ponto Móvel, 02/10/2026).
 *
 * Vem da própria playlist: os itens `institucional` com mídia que o servidor
 * mandou por último. Atualizado a cada playlist do servidor que tenha algum;
 * uma playlist sem institucional (hora toda vendida) não apaga o que já
 * havia. A mídia correspondente é protegida no cache
 * ([br.com.mostrai.player.cache.CacheMidia.proteger]).
 *
 * Institucional nunca gera comprovante (`contabiliza: false`) e nunca reduz
 * obrigação de anunciante: tocá-lo é só não deixar a tela apagada.
 */
class InstitucionalLocal(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    @Volatile
    private var cache: List<ItemPlaylist>? = null

    fun itens(): List<ItemPlaylist> = cache ?: ler().also { cache = it }

    /** Guarda os institucionais desta playlist, se houver; sem nenhum, mantém os anteriores. */
    fun atualizar(playlist: Playlist) {
        val novos = playlist.institucionais().distinctBy { it.contentHash ?: it.url }
        if (novos.isEmpty() || novos == itens()) return
        val json = JSONArray().apply {
            novos.forEach { item ->
                put(
                    JSONObject()
                        .put("criativoId", item.criativoId ?: JSONObject.NULL)
                        .put("url", item.url)
                        .put("duracaoSegundos", item.duracaoSegundos)
                        .put("contentHash", item.contentHash ?: JSONObject.NULL),
                )
            }
        }
        prefs.edit().putString(CHAVE, json.toString()).apply()
        cache = novos
    }

    private fun ler(): List<ItemPlaylist> = runCatching {
        val array = JSONArray(prefs.getString(CHAVE, null) ?: return emptyList())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").ifBlank { null } ?: return@mapNotNull null
            ItemPlaylist(
                itemProgramacaoId = null,
                criativoId = o.optString("criativoId").takeIf { !o.isNull("criativoId") && it.isNotBlank() },
                duracaoSegundos = o.optInt("duracaoSegundos", 0),
                url = url,
                // Fallback nunca vira comprovante, venha de onde vier.
                contabiliza = false,
                contentHash = o.optString("contentHash").takeIf { !o.isNull("contentHash") && it.isNotBlank() },
                institucional = true,
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val ARQUIVO = "mostrai_institucional"
        const val CHAVE = "itens"
    }
}
