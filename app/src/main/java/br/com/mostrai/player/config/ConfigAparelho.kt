package br.com.mostrai.player.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import br.com.mostrai.player.BuildConfig
import kotlin.math.min

/**
 * Configuração persistida do aparelho.
 *
 * Nunca guarda usuário/senha: a tela se autentica apenas pela chave de aparelho
 * revogável emitida no painel admin (veto formal do projeto).
 *
 * Depois do provisionamento, o que é **cotidiano** vem do backend
 * ([ConfigRemota]); o que fica aqui é identidade, credencial, o último config
 * válido e o fallback de campo (rotação e margens), que precisa existir para
 * um técnico conseguir endireitar uma instalação torta numa loja sem internet.
 */
class ConfigAparelho(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    /** Id da tela no cadastro do Mostraí. */
    var dispositivoId: String?
        get() = prefs.getString(CHAVE_DISPOSITIVO, null)
        set(valor) = prefs.edit().putString(CHAVE_DISPOSITIVO, valor).apply()

    /** Chave revogável do aparelho, enviada no header de autenticação. */
    var chaveAparelho: String?
        get() = prefs.getString(CHAVE_APARELHO, null)
        set(valor) = prefs.edit().putString(CHAVE_APARELHO, valor).apply()

    /**
     * Chave nova que o servidor mandou e que ainda **não** provou funcionar.
     *
     * Rotação sem tijolar a tela (Parte 14): a chave em uso só é substituída
     * depois de uma requisição bem-sucedida com a candidata. Se a candidata
     * falhar — servidor voltou atrás, resposta corrompida, relógio errado —
     * o player continua com a antiga e a loja não para. Sobrescrever a chave
     * boa no momento em que a nova chega seria apostar a tela inteira numa
     * resposta HTTP.
     */
    var chaveCandidata: String?
        get() = prefs.getString(CHAVE_APARELHO_CANDIDATA, null)
        set(valor) = prefs.edit().putString(CHAVE_APARELHO_CANDIDATA, valor).apply()

    /** Base da API, ex.: https://exemplo/api. Não vai versionada no repositório. */
    var baseUrl: String?
        get() = prefs.getString(CHAVE_BASE_URL, null)
        set(valor) = prefs.edit().putString(CHAVE_BASE_URL, valor).apply()

    /** Token de uso único do pendrive, apagado assim que vira credencial. */
    var tokenProvisionamento: String?
        get() = prefs.getString(CHAVE_TOKEN_PROVISIONAMENTO, null)
        set(valor) = prefs.edit().putString(CHAVE_TOKEN_PROVISIONAMENTO, valor).apply()

    /**
     * Margem em vmin para compensar moldura física de TV que corta a borda da
     * imagem (overscan) — 4 valores independentes, um por lado, porque a
     * folga de cada TV varia por lado, não só por tela. Cada lado é descrito
     * em termos visuais (o que o dono vê olhando pra tela montada), não em
     * coordenadas físicas do painel — [br.com.mostrai.player.ui.RotacaoTela]
     * aplica a margem depois da rotação, então "topo" sempre significa topo
     * na tela como o operador enxerga, mesmo com `rotacaoTela` diferente de 0.
     */
    var margemVminTopo: Float
        get() = prefs.getFloat(CHAVE_MARGEM_TOPO, 0f)
        set(valor) = prefs.edit().putFloat(CHAVE_MARGEM_TOPO, valor.coerceIn(0f, 10f)).apply()

    var margemVminBase: Float
        get() = prefs.getFloat(CHAVE_MARGEM_BASE, 0f)
        set(valor) = prefs.edit().putFloat(CHAVE_MARGEM_BASE, valor.coerceIn(0f, 10f)).apply()

    var margemVminEsquerda: Float
        get() = prefs.getFloat(CHAVE_MARGEM_ESQUERDA, 0f)
        set(valor) = prefs.edit().putFloat(CHAVE_MARGEM_ESQUERDA, valor.coerceIn(0f, 10f)).apply()

    var margemVminDireita: Float
        get() = prefs.getFloat(CHAVE_MARGEM_DIREITA, 0f)
        set(valor) = prefs.edit().putFloat(CHAVE_MARGEM_DIREITA, valor.coerceIn(0f, 10f)).apply()

    /** Os 4 lados juntos, pra passar pra [br.com.mostrai.player.ui.RotacaoTela] de uma vez. */
    var margensOverscan: MargensOverscan
        get() = MargensOverscan(margemVminTopo, margemVminBase, margemVminEsquerda, margemVminDireita)
        set(valor) {
            margemVminTopo = valor.topo
            margemVminBase = valor.base
            margemVminEsquerda = valor.esquerda
            margemVminDireita = valor.direita
        }

    /**
     * PIN administrativo do player. Sempre [TAMANHO_PIN] dígitos numéricos —
     * é o que o teclado do painel aceita digitar de volta. Um valor fora
     * desse formato nunca é gravado: sem essa guarda, um PIN provisionado
     * errado trancaria o painel pra sempre. Valor inválido é ignorado e loga
     * um aviso, nunca lança exceção — provisionamento não pode derrubar o app.
     *
     * O último PIN válido fica aqui mesmo depois de o backend assumir o
     * controle dele: uma tela offline ainda precisa poder ser aberta para
     * manutenção, e é justamente sem internet que alguém vai precisar.
     */
    var pinPainel: String
        get() = prefs.getString(CHAVE_PIN, PIN_PROVISORIO) ?: PIN_PROVISORIO
        set(valor) {
            if (!ehPinValido(valor)) {
                Log.w(TAG, "PIN ignorado: precisa ter exatamente $TAMANHO_PIN dígitos numéricos")
                return
            }
            prefs.edit().putString(CHAVE_PIN, valor).apply()
        }

    /**
     * Rotação do conteúdo em graus (0, 90, 180 ou 270), para compensar um
     * painel montado fisicamente de lado — o Android não sabe disso sozinho
     * (o framebuffer nativo continua landscape). Qualquer valor fora desse
     * conjunto vira 0 — nunca gira a esmo.
     */
    var rotacaoTela: Int
        get() = prefs.getInt(CHAVE_ROTACAO, 0)
        set(valor) = prefs.edit().putInt(CHAVE_ROTACAO, if (valor in ROTACOES_VALIDAS) valor else 0).apply()

    // ------------------------------------------------------------- contrato V2

    /** Versão de config que o player realmente aplicou. 0 = nunca recebeu. */
    var configVersionAplicada: Int
        get() = prefs.getInt(CHAVE_CONFIG_VERSAO, 0)
        set(valor) = prefs.edit().putInt(CHAVE_CONFIG_VERSAO, valor).apply()

    /** Último corpo de `/config` que foi aplicado com sucesso — o "último válido". */
    var configRemotaJson: String?
        get() = prefs.getString(CHAVE_CONFIG_JSON, null)
        set(valor) = prefs.edit().putString(CHAVE_CONFIG_JSON, valor).apply()

    /** Assinatura dos dados técnicos do último `hello` enviado com sucesso. */
    var assinaturaHello: String?
        get() = prefs.getString(CHAVE_ASSINATURA_HELLO, null)
        set(valor) = prefs.edit().putString(CHAVE_ASSINATURA_HELLO, valor).apply()

    /** ISO 8601 da última playlist buscada do servidor com sucesso. */
    var ultimaPlaylistOkEm: String?
        get() = prefs.getString(CHAVE_ULTIMA_PLAYLIST_OK, null)
        set(valor) = prefs.edit().putString(CHAVE_ULTIMA_PLAYLIST_OK, valor).apply()

    /**
     * Falso enquanto o backend responder 404 às rotas V2. Só muda de ideia
     * quando uma rota V2 responde de verdade — é o que faz o player degradar
     * sozinho para o contrato antigo sem ninguém configurar nada.
     */
    var backendV2Disponivel: Boolean
        get() = prefs.getBoolean(CHAVE_BACKEND_V2, false)
        set(valor) = prefs.edit().putBoolean(CHAVE_BACKEND_V2, valor).apply()

    /** Config remota vigente, reconstruída do último corpo válido. */
    fun configRemota(): ConfigRemota? = configRemotaJson?.let(ConfigRemotaJson::parse)

    fun horarioOperacional(): HorarioOperacional = configRemota()?.horario ?: HorarioOperacional()

    /**
     * Aplica a config nova de uma vez só. Recebe o corpo cru já validado por
     * quem chamou: guardar o texto original (e não campo a campo) é o que
     * permite o player reconstruir a config depois de um reboot sem
     * depender do servidor, e o que mantém a aplicação atômica.
     */
    fun aplicarConfigRemota(config: ConfigRemota, corpoBruto: String) {
        config.margens?.let { margensOverscan = it }
        config.rotacaoTela?.let { rotacaoTela = it }
        config.pinPainel?.let { pinPainel = it }
        configRemotaJson = corpoBruto
        configVersionAplicada = config.versao
    }

    // ------------------------------------------------------------- PIN: rate limit

    /**
     * Bloqueio progressivo por erro de PIN.
     *
     * Sem isto são 10.000 combinações digitáveis num controle remoto sem
     * penalidade nenhuma — alguém com paciência abre o painel de qualquer
     * tela. O atraso cresce (5s, 10s, 20s, … até 5 min) e zera no primeiro
     * acerto; não bloqueia para sempre, porque trancar a manutenção de uma
     * tela em campo é pior que o ataque que isso evita.
     */
    fun pinBloqueadoPorMs(agoraMs: Long = System.currentTimeMillis()): Long {
        val ate = prefs.getLong(CHAVE_PIN_BLOQUEIO_ATE, 0L)
        return (ate - agoraMs).coerceAtLeast(0L)
    }

    fun registrarPinErrado(agoraMs: Long = System.currentTimeMillis()) {
        val erros = prefs.getInt(CHAVE_PIN_ERROS, 0) + 1
        val editor = prefs.edit().putInt(CHAVE_PIN_ERROS, erros)
        if (erros >= ERROS_PIN_ANTES_DE_BLOQUEAR) {
            val passos = erros - ERROS_PIN_ANTES_DE_BLOQUEAR
            val atraso = min(BLOQUEIO_PIN_BASE_MS shl passos, BLOQUEIO_PIN_MAXIMO_MS)
            editor.putLong(CHAVE_PIN_BLOQUEIO_ATE, agoraMs + atraso)
        }
        editor.apply()
    }

    fun registrarPinCerto() {
        prefs.edit().putInt(CHAVE_PIN_ERROS, 0).putLong(CHAVE_PIN_BLOQUEIO_ATE, 0L).apply()
    }

    // ------------------------------------------------------------- estado geral

    val provisionado: Boolean
        get() = !dispositivoId.isNullOrBlank() &&
            !chaveAparelho.isNullOrBlank() &&
            !baseUrl.isNullOrBlank()

    /**
     * Há credencial utilizável? Chave em branco não é credencial (R7): mandar
     * um header vazio fingindo autenticação esconde o problema real — o
     * aparelho não está provisionado — atrás de um 401 genérico.
     */
    val temCredencial: Boolean
        get() = !chaveAparelho.isNullOrBlank() && !dispositivoId.isNullOrBlank() && !baseUrl.isNullOrBlank()

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
     * Aplica a configuração embutida no build (`-PconfigDispositivo`).
     *
     * **Depreciado**: o caminho oficial é um APK universal configurado por
     * `mostrai-config.json` no pendrive. Continua aqui só para não quebrar
     * APKs já gerados por tela; será removido quando não houver nenhum em
     * campo. Ver README, "Provisionamento".
     */
    @Deprecated("Um APK por tela. Use mostrai-config.json no pendrive.")
    fun aplicarConfiguracaoEmbutidaSeNecessaria() {
        if (provisionado) return
        if (BuildConfig.DISPOSITIVO_ID_EMBUTIDO.isBlank()) return

        dispositivoId = BuildConfig.DISPOSITIVO_ID_EMBUTIDO
        if (BuildConfig.CHAVE_APARELHO_EMBUTIDA.isNotBlank()) chaveAparelho = BuildConfig.CHAVE_APARELHO_EMBUTIDA
        if (BuildConfig.BASE_URL_EMBUTIDA.isNotBlank()) baseUrl = BuildConfig.BASE_URL_EMBUTIDA
        if (BuildConfig.PIN_EMBUTIDO.isNotBlank()) pinPainel = BuildConfig.PIN_EMBUTIDO
        BuildConfig.MARGEM_VMIN_TOPO_EMBUTIDA.toFloatOrNull()?.let { margemVminTopo = it }
        BuildConfig.MARGEM_VMIN_BASE_EMBUTIDA.toFloatOrNull()?.let { margemVminBase = it }
        BuildConfig.MARGEM_VMIN_ESQUERDA_EMBUTIDA.toFloatOrNull()?.let { margemVminEsquerda = it }
        BuildConfig.MARGEM_VMIN_DIREITA_EMBUTIDA.toFloatOrNull()?.let { margemVminDireita = it }
        BuildConfig.ROTACAO_TELA_EMBUTIDA.toIntOrNull()?.let { rotacaoTela = it }
    }

    companion object {
        private const val ARQUIVO = "mostrai_config"
        private const val CHAVE_DISPOSITIVO = "dispositivo_id"
        private const val CHAVE_APARELHO = "chave_aparelho"
        private const val CHAVE_APARELHO_CANDIDATA = "chave_aparelho_candidata"
        private const val CHAVE_BASE_URL = "base_url"
        private const val CHAVE_TOKEN_PROVISIONAMENTO = "token_provisionamento"
        private const val CHAVE_MARGEM_TOPO = "margem_vmin_topo"
        private const val CHAVE_MARGEM_BASE = "margem_vmin_base"
        private const val CHAVE_MARGEM_ESQUERDA = "margem_vmin_esquerda"
        private const val CHAVE_MARGEM_DIREITA = "margem_vmin_direita"
        private const val CHAVE_PIN = "pin_painel"
        private const val CHAVE_ROTACAO = "rotacao_tela"
        private const val CHAVE_CONFIG_VERSAO = "config_versao_aplicada"
        private const val CHAVE_CONFIG_JSON = "config_remota_json"
        private const val CHAVE_ASSINATURA_HELLO = "assinatura_hello"
        private const val CHAVE_ULTIMA_PLAYLIST_OK = "ultima_playlist_ok"
        private const val CHAVE_BACKEND_V2 = "backend_v2_disponivel"
        private const val CHAVE_PIN_ERROS = "pin_erros"
        private const val CHAVE_PIN_BLOQUEIO_ATE = "pin_bloqueio_ate"
        private const val TAG = "ConfigAparelho"

        /** Trocado no primeiro provisionamento. Não é segredo, é valor inicial. */
        const val PIN_PROVISORIO = "0000"

        /** Mesmo tamanho aceito pelo teclado do painel. */
        const val TAMANHO_PIN = 4

        const val ERROS_PIN_ANTES_DE_BLOQUEAR = 3
        const val BLOQUEIO_PIN_BASE_MS = 5_000L
        const val BLOQUEIO_PIN_MAXIMO_MS = 5 * 60_000L

        val ROTACOES_VALIDAS = setOf(0, 90, 180, 270)

        /** Só dígitos, sempre [TAMANHO_PIN] deles — nunca letra, símbolo ou outro tamanho. */
        fun ehPinValido(pin: String): Boolean =
            pin.length == TAMANHO_PIN && pin.all { it.isDigit() }
    }
}
