package br.com.mostrai.player.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import br.com.mostrai.player.BuildConfig

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

    /**
     * PIN do painel de manutenção, de [TAMANHO_PIN_MINIMO] a [TAMANHO_PIN_MAXIMO]
     * dígitos numéricos — é esse tamanho que dita quantas teclas o painel
     * espera antes de comparar ([PainelActivity.tamanhoPin]). Um valor fora
     * desse formato nunca é gravado: sem essa guarda, um PIN provisionado
     * errado (por qualquer um dos três caminhos) trancaria o painel pra
     * sempre — nenhuma sequência digitável bateria com ele. Valor inválido é
     * ignorado (mantém o que já estava, o provisório de fábrica se ainda não
     * houver nenhum) e loga um aviso, nunca lança exceção — provisionamento
     * não pode derrubar o app.
     */
    var pinPainel: String
        get() = prefs.getString(CHAVE_PIN, PIN_PROVISORIO) ?: PIN_PROVISORIO
        set(valor) {
            if (!ehPinValido(valor)) {
                Log.w(
                    TAG,
                    "PIN ignorado: precisa ter de $TAMANHO_PIN_MINIMO a $TAMANHO_PIN_MAXIMO dígitos numéricos",
                )
                return
            }
            prefs.edit().putString(CHAVE_PIN, valor).apply()
        }

    /**
     * Rotação do conteúdo em graus (0, 90, 180 ou 270), para compensar um
     * painel montado fisicamente de lado — sinalização digital em espaço
     * estreito costuma instalar uma TV landscape virada, e o Android não
     * sabe disso sozinho (o framebuffer nativo continua landscape).
     * Qualquer valor fora desse conjunto vira 0 — nunca gira a esmo.
     */
    var rotacaoTela: Int
        get() = prefs.getInt(CHAVE_ROTACAO, 0)
        set(valor) = prefs.edit().putInt(CHAVE_ROTACAO, if (valor in ROTACOES_VALIDAS) valor else 0).apply()

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

    /**
     * Aplica a configuração embutida no build (`-PconfigDispositivo=<arquivo>.json`,
     * ver README, "Gerar um APK já configurado por tela") — só na primeira
     * vez, nunca sobrescreve um provisionamento que já existe. É o que
     * permite instalar por pendrive um APK já pronto para uma tela
     * específica, sem precisar de adb depois.
     */
    fun aplicarConfiguracaoEmbutidaSeNecessaria() {
        if (provisionado) return
        if (BuildConfig.DISPOSITIVO_ID_EMBUTIDO.isBlank()) return

        dispositivoId = BuildConfig.DISPOSITIVO_ID_EMBUTIDO
        if (BuildConfig.CHAVE_APARELHO_EMBUTIDA.isNotBlank()) chaveAparelho = BuildConfig.CHAVE_APARELHO_EMBUTIDA
        if (BuildConfig.BASE_URL_EMBUTIDA.isNotBlank()) baseUrl = BuildConfig.BASE_URL_EMBUTIDA
        if (BuildConfig.PIN_EMBUTIDO.isNotBlank()) pinPainel = BuildConfig.PIN_EMBUTIDO
        BuildConfig.MARGEM_VMIN_EMBUTIDA.toFloatOrNull()?.let { margemVmin = it }
        BuildConfig.ROTACAO_TELA_EMBUTIDA.toIntOrNull()?.let { rotacaoTela = it }
    }

    companion object {
        private const val ARQUIVO = "mostrai_config"
        private const val CHAVE_DISPOSITIVO = "dispositivo_id"
        private const val CHAVE_APARELHO = "chave_aparelho"
        private const val CHAVE_BASE_URL = "base_url"
        private const val CHAVE_MARGEM = "margem_vmin"
        private const val CHAVE_PIN = "pin_painel"
        private const val CHAVE_ROTACAO = "rotacao_tela"
        private const val TAG = "ConfigAparelho"

        /** Trocado no primeiro provisionamento. Não é segredo, é valor inicial. */
        const val PIN_PROVISORIO = "0000"

        const val TAMANHO_PIN_MINIMO = 4
        const val TAMANHO_PIN_MAXIMO = 6

        val ROTACOES_VALIDAS = setOf(0, 90, 180, 270)

        /** Só dígitos, entre [TAMANHO_PIN_MINIMO] e [TAMANHO_PIN_MAXIMO] deles. */
        fun ehPinValido(pin: String): Boolean =
            pin.length in TAMANHO_PIN_MINIMO..TAMANHO_PIN_MAXIMO && pin.all { it.isDigit() }
    }
}
