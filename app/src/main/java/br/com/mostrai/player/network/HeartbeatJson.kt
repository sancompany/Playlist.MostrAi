package br.com.mostrai.player.network

import br.com.mostrai.player.estado.EstadoPlayer
import org.json.JSONObject

/**
 * Corpo e resposta de `POST /player/:dispositivoId/heartbeat` (contrato §5).
 * Só os 5 campos do contrato vão; a versão do Player viaja no header.
 */
object HeartbeatJson {

    data class Erro(val codigo: String, val mensagem: String?, val ocorreuEm: String?)

    data class Corpo(
        val estado: EstadoPlayer,
        val configVersionAplicada: Int,
        /** Criativo comercial no ar agora; null = nenhum. */
        val criativoId: String?,
        /** null = sem erro — o servidor limpa o anterior. */
        val erro: Erro?,
        val filaPendentes: Int,
        val filaMaisAntigoEm: String?,
    )

    fun corpo(dados: Corpo): String = JSONObject().apply {
        put("estado", dados.estado.name)
        put("configVersionAplicada", dados.configVersionAplicada)
        dados.criativoId?.let { put("criativoId", it) }
        put(
            "erro",
            dados.erro?.let {
                JSONObject()
                    .put("codigo", it.codigo)
                    .put("mensagem", it.mensagem ?: JSONObject.NULL)
                    .put("ocorreuEm", it.ocorreuEm ?: JSONObject.NULL)
            } ?: JSONObject.NULL,
        )
        put(
            "fila",
            JSONObject()
                .put("pendentes", dados.filaPendentes)
                .put("maisAntigoEm", dados.filaMaisAntigoEm ?: JSONObject.NULL),
        )
    }.toString()

    data class Resposta(
        /** Diferente da aplicada → `GET /config`. */
        val configVersion: Int? = null,
        /** Vem `true` uma vez por mudança → buscar a playlist na hora. */
        val atualizarPlaylist: Boolean = false,
    )

    /**
     * `null` quando o corpo não é JSON (BUG-034): uma página de erro servida
     * com 200 não é "nada a fazer". Corpo vazio vale como resposta vazia.
     */
    fun parseResposta(corpoBruto: String): Resposta? = runCatching {
        if (corpoBruto.isBlank()) return Resposta()
        val json = JSONObject(corpoBruto)
        Resposta(
            configVersion = if (json.has("configVersion") && !json.isNull("configVersion")) {
                json.optInt("configVersion", -1).takeIf { it >= 0 }
            } else {
                null
            },
            atualizarPlaylist = json.optJSONObject("playlist")?.optBoolean("atualizar", false) ?: false,
        )
    }.getOrNull()
}
