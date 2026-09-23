package br.com.mostrai.player.network

import br.com.mostrai.player.playlist.ItemPlaylist
import br.com.mostrai.player.playlist.Playlist
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lê a resposta de `/playlist` nas duas formas possíveis (seção 6.6:
 * compatibilidade).
 *
 * O contrato novo (envelope com `versaoContrato`) ainda não existe no
 * backend — está sendo implementado em paralelo, em outra sessão. Os nomes
 * de campo do envelope seguem a semântica descrita no contrato fechado com
 * o GPT; a chave que embrulha a lista de itens (`itens`) é uma suposição
 * razoável a confirmar quando o backend publicar o contrato de verdade.
 */
object PlaylistJson {

    fun parse(corpoBruto: String): Playlist {
        val corpo = corpoBruto.trim()
        return if (corpo.startsWith("[")) {
            parseLegado(JSONArray(corpo))
        } else {
            parseEnvelope(JSONObject(corpo))
        }
    }

    private fun parseEnvelope(json: JSONObject): Playlist {
        val itensJson = json.optJSONArray("itens") ?: JSONArray()
        val itens = (0 until itensJson.length()).map { i ->
            val item = itensJson.getJSONObject(i)
            ItemPlaylist(
                itemProgramacaoId = item.stringOuNulo("itemProgramacaoId"),
                criativoId = item.stringOuNulo("criativoId"),
                duracaoSegundos = item.optInt("duracaoSegundos", 0),
                url = item.stringOuNulo("url"),
                anuncianteId = item.stringOuNulo("anuncianteId"),
                autoanuncio = item.optBoolean("autoanuncio", false),
                institucional = item.optBoolean("institucional", false),
                contabiliza = item.optBoolean("contabiliza", false),
                contentHash = item.stringOuNulo("contentHash")?.lowercase(),
            )
        }
        return Playlist(
            versaoContrato = json.optInt("versaoContrato", 1),
            janelaId = json.stringOuNulo("janelaId"),
            janelaInicio = json.stringOuNulo("janelaInicio"),
            janelaFim = json.stringOuNulo("janelaFim"),
            servidorAgora = json.stringOuNulo("servidorAgora"),
            itens = itens,
        )
    }

    private fun parseLegado(json: JSONArray): Playlist {
        val itens = (0 until json.length()).map { i ->
            val item = json.getJSONObject(i)
            val institucional = item.optBoolean("institucional", false)
            val autoanuncio = item.optBoolean("autoanuncio", false)
            ItemPlaylist(
                itemProgramacaoId = null,
                criativoId = null,
                duracaoSegundos = item.optInt("duracaoSegundos", 0),
                url = item.stringOuNulo("url"),
                anuncianteId = item.stringOuNulo("anuncianteId"),
                autoanuncio = autoanuncio,
                institucional = institucional,
                // No contrato antigo não existe o campo explícito: institucional
                // e autoanúncio nunca contam, o resto (tem anuncianteId) conta.
                contabiliza = !institucional && !autoanuncio,
            )
        }
        return Playlist(
            versaoContrato = null,
            janelaId = null,
            janelaInicio = null,
            janelaFim = null,
            servidorAgora = null,
            itens = itens,
        )
    }

    private fun JSONObject.stringOuNulo(chave: String): String? =
        if (has(chave) && !isNull(chave)) getString(chave) else null
}
