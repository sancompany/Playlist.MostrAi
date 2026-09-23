package br.com.mostrai.player.cache

import br.com.mostrai.player.playlist.ItemPlaylist
import java.security.MessageDigest

/**
 * Chave de arquivo de cache para um item da playlist.
 *
 * Três identidades possíveis, em ordem de confiabilidade decrescente:
 *
 * 1. **`contentHash`** (contrato V2) — endereçamento por conteúdo. O nome do
 *    arquivo É o SHA-256 do que ele contém, então o cache se autoverifica:
 *    dá para conferir o download antes de promovê-lo, dois criativos com a
 *    mesma mídia compartilham um arquivo só, e um `criativoId` reaproveitado
 *    com arquivo novo naturalmente cai em outra chave.
 * 2. **`criativoId`** (contrato V1) — depende da garantia contratual de que
 *    `criativoId → url` é imutável. Se o backend quebrar essa promessa, o
 *    aparelho serve mídia errada sem ter como perceber; é exatamente por isso
 *    que `contentHash` existe e tem precedência.
 * 3. **hash da URL** (modo degradado) — sem `criativoId`, a própria URL é a
 *    única identidade disponível.
 *
 * Função pura, sem dependência de Android, de propósito — testável sem
 * Context nem framework de teste especial.
 */
object ChaveCache {

    fun paraItem(item: ItemPlaylist): String? {
        val hash = item.contentHash
        if (!hash.isNullOrBlank() && ehHexSha256(hash)) return "sha256-${hash.lowercase()}"

        val criativoId = item.criativoId
        if (!criativoId.isNullOrBlank()) return "criativo-${sanitizar(criativoId)}"

        val url = item.url ?: return null
        return "url-${sha256(url)}"
    }

    /**
     * Um `contentHash` que não é um SHA-256 hexadecimal de 64 caracteres é
     * dado ruim, não conteúdo novo: cair no fallback é melhor que criar uma
     * chave de cache a partir de lixo e depois nunca conseguir validá-la.
     */
    fun ehHexSha256(valor: String): Boolean =
        valor.length == 64 && valor.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun sanitizar(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun sha256(texto: String): String = paraHex(
        MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8))
    )

    fun paraHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
