package br.com.mostrai.player.playlist

/**
 * Um item da hora (contrato §7). Os identificadores são opacos: o Player
 * nunca os interpreta, só devolve no proof-of-play. `itemProgramacaoId`
 * identifica o SLOT, não o anunciante.
 */
data class ItemPlaylist(
    val itemProgramacaoId: String?,
    val criativoId: String?,
    val duracaoSegundos: Int,
    /** Sem url, o Player mostra o cartão local pelo tempo do item. */
    val url: String?,
    /** `true` = gera proof-of-play ao terminar. Institucional e autoanúncio vêm `false`. */
    val contabiliza: Boolean,
    /**
     * SHA-256 do arquivo, hexadecimal minúsculo: a identidade física do
     * conteúdo. O cache guarda por ele e confere o download contra ele.
     */
    val contentHash: String? = null,
)

/** A programação de uma hora cheia, congelada no servidor. */
data class Playlist(
    val janelaId: String?,
    /** ISO 8601, relógio do servidor. */
    val janelaInicio: String?,
    /** Instante da resposta no relógio do servidor. */
    val servidorAgora: String?,
    val itens: List<ItemPlaylist>,
) {
    companion object {
        /** Nada para tocar: a tela mostra o cartão local e tenta de novo. */
        val VAZIA = Playlist(janelaId = null, janelaInicio = null, servidorAgora = null, itens = emptyList())
    }
}
