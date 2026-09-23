package br.com.mostrai.player.update

import br.com.mostrai.player.cache.ChaveCache
import org.json.JSONObject

/**
 * O que o backend diz sobre a versão nova, dentro da resposta do heartbeat.
 *
 * `sha256` não é opcional por decisão: sem ele o player não tem como saber
 * se baixou o APK que o servidor quis mandar, e instalar um binário não
 * verificado numa frota é exatamente o tipo de coisa que não se conserta
 * remotamente depois. Manifesto sem hash válido é manifesto ignorado.
 */
data class UpdateManifesto(
    val disponivel: Boolean,
    val obrigatorio: Boolean,
    val versao: String,
    val build: Int,
    val url: String,
    val sha256: String,
    val tamanhoBytes: Long?,
) {
    companion object {
        fun parse(json: JSONObject): UpdateManifesto? {
            if (!json.optBoolean("available", json.optBoolean("disponivel", false))) return null

            val url = json.textoOuNulo("url") ?: return null
            val sha256 = json.textoOuNulo("sha256")?.lowercase() ?: return null
            if (!ChaveCache.ehHexSha256(sha256)) return null

            val build = json.optInt("build", -1).takeIf { it > 0 } ?: return null

            return UpdateManifesto(
                disponivel = true,
                obrigatorio = json.optBoolean("required", json.optBoolean("obrigatorio", false)),
                versao = json.textoOuNulo("version") ?: json.textoOuNulo("versao") ?: build.toString(),
                build = build,
                url = url,
                sha256 = sha256,
                tamanhoBytes = json.optLong("size", -1L).takeIf { it > 0 },
            )
        }

        private fun JSONObject.textoOuNulo(chave: String): String? =
            if (has(chave) && !isNull(chave)) getString(chave).ifBlank { null } else null
    }
}

/** Onde o player está no ciclo de uma atualização. Persistido — sobrevive a reboot. */
enum class EstadoUpdate {
    NONE,
    AVAILABLE,
    DOWNLOADING,
    READY,
    INSTALL_REQUESTED,
    DEFERRED,
    FAILED,
}
