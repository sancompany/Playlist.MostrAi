package br.com.mostrai.player.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuração persistida do aparelho.
 *
 * Nunca guarda usuário/senha: a tela se autentica apenas pela chave de aparelho
 * revogável emitida no painel admin (veto formal do projeto).
 */
class ConfigAparelho(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    /** Id da tela no cadastro do Mostraí. */
    var dispositivoId: String?
        get() = prefs.getString(CHAVE_DISPOSITIVO, null)
        set(valor) = prefs.edit().putString(CHAVE_DISPOSITIVO, valor).apply()

    /** Chave revogável do aparelho, enviada no header X-Aparelho-Id. */
    var chaveAparelho: String?
        get() = prefs.getString(CHAVE_APARELHO, null)
        set(valor) = prefs.edit().putString(CHAVE_APARELHO, valor).apply()

    /** Base da API, ex.: https://exemplo/api. Não vai versionada no repositório. */
    var baseUrl: String?
        get() = prefs.getString(CHAVE_BASE_URL, null)
        set(valor) = prefs.edit().putString(CHAVE_BASE_URL, valor).apply()

    /**
     * Margem em vmin para compensar moldura física de TV que corta a borda da
     * imagem (overscan). Mesma ideia do parâmetro do player web.
     */
    var margemVmin: Float
        get() = prefs.getFloat(CHAVE_MARGEM, 0f)
        set(valor) = prefs.edit().putFloat(CHAVE_MARGEM, valor.coerceIn(0f, 10f)).apply()

    /** PIN do painel de manutenção. Provisório até a decisão do PIN universal. */
    var pinPainel: String
        get() = prefs.getString(CHAVE_PIN, PIN_PROVISORIO) ?: PIN_PROVISORIO
        set(valor) = prefs.edit().putString(CHAVE_PIN, valor).apply()

    val provisionado: Boolean
        get() = !dispositivoId.isNullOrBlank() &&
            !chaveAparelho.isNullOrBlank() &&
            !baseUrl.isNullOrBlank()

    /**
     * Atraso determinístico de 0 a 29 segundos derivado da chave do aparelho,
     * para que as telas da rede não batam no servidor no mesmo segundo na
     * virada da hora. Mesma tela, mesmo atraso, sempre — e sem depender do
     * relógio local.
     */
    fun atrasoViradaSegundos(): Int {
        val semente = chaveAparelho ?: dispositivoId ?: return 0
        var hash = 0
        for (c in semente) {
            hash = (hash * 31 + c.code) and 0x7fffffff
        }
        return hash % 30
    }

    companion object {
        private const val ARQUIVO = "mostrai_config"
        private const val CHAVE_DISPOSITIVO = "dispositivo_id"
        private const val CHAVE_APARELHO = "chave_aparelho"
        private const val CHAVE_BASE_URL = "base_url"
        private const val CHAVE_MARGEM = "margem_vmin"
        private const val CHAVE_PIN = "pin_painel"

        /** Trocado no primeiro provisionamento. Não é segredo, é valor inicial. */
        const val PIN_PROVISORIO = "0000"
    }
}
