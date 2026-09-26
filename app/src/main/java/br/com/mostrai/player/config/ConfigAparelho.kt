package br.com.mostrai.player.config

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.min

/**
 * O que o aparelho guarda (contrato §10): a credencial e a última config
 * aplicada. Servidor, orientação e intervalos não são estado — são
 * constantes do APK ([br.com.mostrai.player.Produto]).
 *
 * Nunca guarda usuário/senha nem o código de instalação: a tela se
 * autentica só pela `chaveAparelho`, que o operador nunca vê.
 */
class ConfigAparelho(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    /**
     * ID humano da tela (`M-0235`). Fica mesmo depois de a credencial ser
     * revogada: não é segredo, e preenche a tela de instalação da próxima vez.
     */
    val dispositivoId: String?
        get() = prefs.getString(CHAVE_DISPOSITIVO, null)?.ifBlank { null }

    /** Credencial permanente. Nunca vai para log, tela, diário ou heartbeat. */
    val chaveAparelho: String?
        get() = prefs.getString(CHAVE_APARELHO, null)?.ifBlank { null }

    val provisionado: Boolean
        get() = dispositivoId != null && chaveAparelho != null

    /**
     * Grava as duas de uma vez, síncrono. `false` = o disco recusou, e o
     * aparelho **não** conta como provisionado — nunca fica com metade.
     */
    fun gravarCredenciais(dispositivoId: String, chave: String): Boolean {
        val trocouDeTela = this.dispositivoId != null && this.dispositivoId != dispositivoId
        val editor = prefs.edit()
            .putString(CHAVE_DISPOSITIVO, dispositivoId)
            .putString(CHAVE_APARELHO, chave)
        // Outra tela: a config da anterior não vale para esta.
        if (trocouDeTela) editor.remove(CHAVE_CONFIG_VERSAO).remove(CHAVE_CONFIG_JSON)
        return editor.commit()
    }

    /**
     * 401 numa rota autenticada (contrato §4): apaga a credencial — mas só se
     * ela ainda for a que levou o 401. Uma resposta atrasada, de antes de um
     * reprovisionamento, não pode apagar a credencial nova.
     */
    @Synchronized
    fun esquecerCredencialSeFor(chaveRecusada: String): Boolean {
        if (chaveAparelho != chaveRecusada) return false
        return prefs.edit().remove(CHAVE_APARELHO).commit()
    }

    // ------------------------------------------------------------------ config

    /** Versão da config que está valendo na TV. 0 = nunca recebeu. */
    val configVersionAplicada: Int
        get() = prefs.getInt(CHAVE_CONFIG_VERSAO, 0)

    /**
     * Versão e corpo numa escrita só, síncrona: a versão confirmada ao
     * servidor nunca fica à frente do que está de fato gravado.
     */
    fun aplicarConfig(config: ConfigRemota, corpoBruto: String): Boolean = prefs.edit()
        .putString(CHAVE_CONFIG_JSON, corpoBruto)
        .putInt(CHAVE_CONFIG_VERSAO, config.versao)
        .commit()

    @Volatile
    private var cacheConfig: Pair<String, ConfigRemota?>? = null

    /** Última config aplicada — é ela que decide margem, horário e PIN offline. */
    fun configAtual(): ConfigRemota? {
        val corpo = prefs.getString(CHAVE_CONFIG_JSON, null) ?: return null
        cacheConfig?.let { (bruto, config) -> if (bruto == corpo) return config }
        return ConfigRemotaJson.parse(corpo).also { cacheConfig = corpo to it }
    }

    val margens: MargensOverscan get() = configAtual()?.margens ?: MargensOverscan()

    fun horarioOperacional(): HorarioOperacional = configAtual()?.horario ?: HorarioOperacional()

    /** `null` = sem PIN recebido: saída autorizada indisponível. Nunca há PIN padrão. */
    val pinSaida: String? get() = configAtual()?.pinSaida

    // ------------------------------------------------------------ PIN: bloqueio

    /**
     * Bloqueio progressivo por erro de PIN: sem ele, 10 mil combinações de 4
     * dígitos se testam no controle remoto sem custo. Cresce (5 s, 10 s, 20
     * s… até 5 min) e zera no acerto; nunca é permanente.
     */
    fun pinBloqueadoPorMs(agoraMs: Long = System.currentTimeMillis()): Long =
        (prefs.getLong(CHAVE_PIN_BLOQUEIO_ATE, 0L) - agoraMs).coerceAtLeast(0L)

    fun registrarPinErrado(agoraMs: Long = System.currentTimeMillis()) {
        val erros = prefs.getInt(CHAVE_PIN_ERROS, 0) + 1
        val editor = prefs.edit().putInt(CHAVE_PIN_ERROS, erros)
        if (erros >= ERROS_PIN_ANTES_DE_BLOQUEAR) {
            val passos = (erros - ERROS_PIN_ANTES_DE_BLOQUEAR).coerceAtMost(20)
            val atraso = min(BLOQUEIO_PIN_BASE_MS shl passos, BLOQUEIO_PIN_MAXIMO_MS)
            editor.putLong(CHAVE_PIN_BLOQUEIO_ATE, agoraMs + atraso)
        }
        editor.apply()
    }

    fun registrarPinCerto() {
        prefs.edit().putInt(CHAVE_PIN_ERROS, 0).putLong(CHAVE_PIN_BLOQUEIO_ATE, 0L).apply()
    }

    // ------------------------------------------------------------------ outros

    /**
     * Atraso determinístico de 0 a 29 s derivado do ID da tela, para as telas
     * da rede não baterem juntas no servidor na virada da hora.
     */
    fun atrasoViradaSegundos(): Int {
        val semente = dispositivoId ?: return 0
        var hash = 0
        for (c in semente) hash = (hash * 31 + c.code) and 0x7fffffff
        return hash % 30
    }

    /**
     * Apaga o que versões anteriores gravavam e o contrato MVP não usa mais
     * (servidor, rotação, token de pendrive, chave candidata, PIN por tela…).
     * Idempotente; chamado no boot.
     */
    fun limparChavesObsoletas() {
        if (CHAVES_OBSOLETAS.none(prefs::contains)) return
        val editor = prefs.edit()
        CHAVES_OBSOLETAS.forEach { editor.remove(it) }
        editor.apply()
    }

    companion object {
        const val ARQUIVO = "mostrai_config"
        private const val CHAVE_DISPOSITIVO = "dispositivo_id"
        private const val CHAVE_APARELHO = "chave_aparelho"
        private const val CHAVE_CONFIG_VERSAO = "config_versao_aplicada"
        private const val CHAVE_CONFIG_JSON = "config_remota_json"
        private const val CHAVE_PIN_ERROS = "pin_erros"
        private const val CHAVE_PIN_BLOQUEIO_ATE = "pin_bloqueio_ate"

        private val CHAVES_OBSOLETAS = listOf(
            "base_url", "rotacao_tela", "token_provisionamento", "chave_aparelho_candidata",
            "pin_painel", "margem_vmin_topo", "margem_vmin_base", "margem_vmin_esquerda",
            "margem_vmin_direita", "assinatura_hello", "backend_v2_disponivel", "ultima_playlist_ok",
        )

        const val ERROS_PIN_ANTES_DE_BLOQUEAR = 3
        const val BLOQUEIO_PIN_BASE_MS = 5_000L
        const val BLOQUEIO_PIN_MAXIMO_MS = 5 * 60_000L
    }
}
