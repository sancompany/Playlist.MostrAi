package br.com.mostrai.player.playlist

/**
 * Um item da programação de uma hora.
 *
 * Os identificadores vêm do backend e são opacos: o app nunca os interpreta,
 * só devolve. `itemProgramacaoId` identifica o SLOT, não o anunciante — o mesmo
 * anunciante se repete legitimamente na mesma hora.
 */
data class ItemPlaylist(
    val itemProgramacaoId: String?,
    val criativoId: String?,
    val duracaoSegundos: Int,
    val url: String?,
    val anuncianteId: String?,
    val autoanuncio: Boolean,
    val institucional: Boolean,
    /** Institucional e autoanúncio não geram evento de exibição. */
    val contabiliza: Boolean,
    /**
     * SHA-256 do arquivo de mídia, hexadecimal minúsculo, quando o backend o
     * fornece (contrato V2). É a **identidade física** do conteúdo: o cache
     * guarda por hash e verifica o download contra ele, e com isso deixa de
     * depender da promessa não verificável de que `criativoId → url` nunca
     * muda. `criativoId` segue sendo a identidade de domínio, usada no
     * proof-of-play. Nulo em playlist V1 — ver `cache.ChaveCache`.
     */
    val contentHash: String? = null,
)

/**
 * A programação de uma janela (uma hora congelada de uma tela).
 *
 * Em modo degradado (servidor ainda no contrato antigo) `versaoContrato` é nulo,
 * não há `janelaId`, e o comprovante cai no formato antigo `{anuncianteId}`.
 */
data class Playlist(
    val versaoContrato: Int?,
    val janelaId: String?,
    /** ISO 8601 com offset, relógio do servidor. */
    val janelaInicio: String?,
    val janelaFim: String?,
    /** Instante da resposta no relógio do servidor. */
    val servidorAgora: String?,
    val itens: List<ItemPlaylist>,
) {
    val modoDegradado: Boolean get() = versaoContrato == null

    companion object {
        /** Playlist mostrada enquanto a tela não tem programação utilizável. */
        fun somenteInstitucional(duracaoSegundos: Int = 10): Playlist = Playlist(
            versaoContrato = null,
            janelaId = null,
            janelaInicio = null,
            janelaFim = null,
            servidorAgora = null,
            itens = listOf(
                ItemPlaylist(
                    itemProgramacaoId = null,
                    criativoId = null,
                    duracaoSegundos = duracaoSegundos,
                    url = null,
                    anuncianteId = null,
                    autoanuncio = false,
                    institucional = true,
                    contabiliza = false,
                )
            ),
        )
    }
}
