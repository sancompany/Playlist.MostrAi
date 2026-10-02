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
    /**
     * Preenchimento da rede (vídeo institucional da Mostraí). É o único
     * conteúdo que o Player pode seguir tocando quando a programação
     * comercial vence sem internet (offline não autoriza veiculação).
     */
    val institucional: Boolean = false,
)

/** A programação de uma hora cheia, congelada no servidor. */
data class Playlist(
    val janelaId: String?,
    /** ISO 8601, relógio do servidor. */
    val janelaInicio: String?,
    /** Instante da resposta no relógio do servidor. */
    val servidorAgora: String?,
    val itens: List<ItemPlaylist>,
    /** Fim da hora autorizada (ISO 8601, relógio do servidor). */
    val janelaFim: String? = null,
) {
    /**
     * Até quando esta programação está autorizada, em ms de época:
     * `janelaFim`, ou uma hora depois de `janelaInicio`. Null quando não há
     * como saber — e aí ela não vale nada sem o servidor (ver
     * [comercialAutorizadoEm]).
     */
    fun validaAteMs(): Long? =
        instante(janelaFim) ?: instante(janelaInicio)?.plus(DURACAO_JANELA_MS)

    /**
     * Offline não autoriza veiculação: o comercial desta playlist só toca
     * enquanto o "agora" confiável ([RelogioConfiavel]) estiver antes do fim
     * da janela. Sem "agora" confiável ou sem fim conhecido, não toca.
     */
    fun comercialAutorizadoEm(agoraMs: Long?): Boolean {
        val ate = validaAteMs() ?: return false
        return agoraMs != null && agoraMs < ate
    }

    /** O que pode tocar quando a programação vence: só o institucional com mídia. */
    fun institucionais(): List<ItemPlaylist> = itens.filter { it.institucional && !it.url.isNullOrBlank() }

    companion object {
        const val DURACAO_JANELA_MS = 60 * 60 * 1000L

        private fun instante(iso: String?): Long? = iso?.let {
            runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        }

        /** Nada para tocar: a tela mostra o cartão local e tenta de novo. */
        val VAZIA = Playlist(janelaId = null, janelaInicio = null, servidorAgora = null, itens = emptyList())
    }
}
