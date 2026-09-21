package br.com.mostrai.player.cache

import br.com.mostrai.player.playlist.ItemPlaylist
import java.security.MessageDigest

/**
 * Chave de arquivo de cache para um item da playlist.
 *
 * A imutabilidade `criativoId → url` (seção 6.1 do contrato) é o que permite
 * usar `criativoId` como chave sem revalidar nada: uma vez baixado, o
 * arquivo nunca precisa ser conferido de novo contra o servidor. Em modo
 * degradado (sem `criativoId`), a chave cai para um hash da própria URL —
 * sem a mesma garantia de imutabilidade do contrato novo, mas é a única
 * identidade disponível.
 *
 * Função pura, sem dependência de Android, de propósito — testável sem
 * Context nem framework de teste especial.
 */
object ChaveCache {

    fun paraItem(item: ItemPlaylist): String? {
        val criativoId = item.criativoId
        if (!criativoId.isNullOrBlank()) return "criativo-${sanitizar(criativoId)}"

        val url = item.url ?: return null
        return "url-${sha256(url)}"
    }

    private fun sanitizar(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun sha256(texto: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
