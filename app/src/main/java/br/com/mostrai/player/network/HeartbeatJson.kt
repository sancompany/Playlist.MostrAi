package br.com.mostrai.player.network

import br.com.mostrai.player.config.MargensOverscan
import br.com.mostrai.player.estado.EstadoPlayer
import br.com.mostrai.player.update.UpdateManifesto
import org.json.JSONObject

/**
 * Corpo e resposta de `POST /player/:dispositivoId/heartbeat`.
 *
 * O payload é pequeno de propósito. Dado que nunca muda (fabricante, modelo,
 * resolução, versão do Android) vai uma vez em [HelloJson], no boot — mandar
 * isso a cada 5 minutos seriam ~105 mil repetições por ano, por tela, do
 * mesmo texto. Aqui só vai o que muda.
 *
 * O contrato antigo continua aceito na resposta: o backend de hoje devolve
 * `margens` no heartbeat (RN-17) e ainda não conhece `configVersion`. As duas
 * formas convivem até a migração terminar.
 */
object HeartbeatJson {

    /** O que o player conta ao servidor a cada ciclo. */
    data class Corpo(
        val estado: EstadoPlayer,
        val configVersionAplicada: Int,
        val criativoId: String?,
        val ultimaPlaylistOkEm: String?,
        val filaPendentes: Int,
        val filaMaisAntigoEm: String?,
        val erroCodigo: String?,
        val erroEm: String?,
        val erroMensagem: String?,
        val desvioRelogioMs: Long?,
        val updateEstado: String?,
    )

    fun corpo(dados: Corpo): String = JSONObject().apply {
        put("versaoContrato", CONTRATO)
        put("estado", dados.estado.name)
        put("configVersionAplicada", dados.configVersionAplicada)
        dados.criativoId?.let { put("criativoId", it) }
        dados.ultimaPlaylistOkEm?.let { put("ultimaPlaylistOkEm", it) }
        put(
            "fila",
            JSONObject().apply {
                put("pendentes", dados.filaPendentes)
                put("maisAntigoEm", dados.filaMaisAntigoEm ?: JSONObject.NULL)
            },
        )
        put(
            "erro",
            if (dados.erroCodigo == null) {
                JSONObject.NULL
            } else {
                JSONObject().apply {
                    put("codigo", dados.erroCodigo)
                    put("ocorreuEm", dados.erroEm ?: JSONObject.NULL)
                    put("mensagem", dados.erroMensagem ?: JSONObject.NULL)
                }
            },
        )
        dados.desvioRelogioMs?.let { put("desvioRelogioMs", it) }
        dados.updateEstado?.let { put("update", JSONObject().put("estado", it)) }
    }.toString()

    data class Resposta(
        val servidorAgora: String? = null,
        val configVersion: Int? = null,
        /** Compatibilidade V1: o backend atual entrega margens por aqui. */
        val margens: MargensOverscan? = null,
        val atualizarPlaylist: Boolean = false,
        val update: UpdateManifesto? = null,
        val novaChave: String? = null,
    )

    fun parseResposta(corpoBruto: String): Resposta = runCatching {
        val json = JSONObject(corpoBruto)
        Resposta(
            servidorAgora = json.textoOuNulo("servidorAgora"),
            configVersion = if (json.has("configVersion") && !json.isNull("configVersion")) {
                json.optInt("configVersion", -1).takeIf { it >= 0 }
            } else {
                null
            },
            margens = json.optJSONObject("margens")?.let(::parseMargens),
            atualizarPlaylist = json.optJSONObject("playlist")?.optBoolean("atualizar", false) ?: false,
            update = json.optJSONObject("update")?.let(UpdateManifesto::parse),
            novaChave = json.textoOuNulo("novaChave"),
        )
    }.getOrElse { Resposta() }

    /**
     * `superior`/`inferior` viram `topo`/`base` para bater com o nome que
     * [MargensOverscan] já usava antes deste contrato existir — mesma
     * margem, nome já usado nos caminhos de provisionamento local.
     */
    fun parseMargens(margens: JSONObject): MargensOverscan = MargensOverscan(
        topo = margens.optDouble("superior", 0.0).toFloat().semNaN(),
        base = margens.optDouble("inferior", 0.0).toFloat().semNaN(),
        esquerda = margens.optDouble("esquerda", 0.0).toFloat().semNaN(),
        direita = margens.optDouble("direita", 0.0).toFloat().semNaN(),
    )

    /** Compatibilidade com o formato de hoje, usado antes do heartbeat V2. */
    fun parseMargens(corpoBruto: String): MargensOverscan? = runCatching {
        val margens = JSONObject(corpoBruto).optJSONObject("margens") ?: return null
        parseMargens(margens)
    }.getOrNull()

    private fun Float.semNaN(): Float = if (isNaN()) 0f else this

    private fun JSONObject.textoOuNulo(chave: String): String? =
        if (has(chave) && !isNull(chave)) getString(chave).ifBlank { null } else null

    const val CONTRATO = 2
}
