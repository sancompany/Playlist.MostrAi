package br.com.mostrai.player.config

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * Provisionamento por arquivo externo: um `mostrai-config.json` no mesmo
 * pendrive usado para instalar o APK. Alternativa a `-PconfigDispositivo`
 * (README, "Gerar um APK já configurado por tela") para quem prefere editar
 * um JSON toda vez que muda a tela, em vez de recompilar um APK — mesmo
 * formato dos arquivos dentro de `dispositivos/`, um único APK genérico
 * para todas as telas.
 *
 * Só age enquanto o aparelho não está provisionado — nunca sobrescreve uma
 * configuração que já existe (mesma regra de
 * [ConfigAparelho.aplicarConfiguracaoEmbutidaSeNecessaria]).
 */
object ConfigExterna {

    const val NOME_ARQUIVO = "mostrai-config.json"

    /** O que veio do JSON — campo ausente ou vazio vira null, nunca sobrescreve nada. */
    data class Dados(
        val dispositivoId: String? = null,
        val chaveAparelho: String? = null,
        /**
         * Formato preferencial: em vez do segredo definitivo, um token que o
         * servidor queima na primeira troca. Um pendrive esquecido numa loja
         * ou perdido no caminho expõe um token já inutilizado, não a
         * credencial permanente de uma tela em operação — e o mesmo pendrive
         * não provisiona duas TVs por engano.
         */
        val tokenProvisionamento: String? = null,
        val baseUrl: String? = null,
        val pin: String? = null,
        val margemVminTopo: Float? = null,
        val margemVminBase: Float? = null,
        val margemVminEsquerda: Float? = null,
        val margemVminDireita: Float? = null,
        val rotacaoTela: Int? = null,
    )

    /** Função pura — testável sem Android, sem arquivo, sem permissão. */
    fun parse(textoJson: String): Dados? = runCatching {
        val json = JSONObject(textoJson)
        Dados(
            dispositivoId = json.optString("dispositivoId").ifBlank { null },
            chaveAparelho = json.optString("chaveAparelho").ifBlank { null },
            tokenProvisionamento = json.optString("tokenProvisionamento").ifBlank { null },
            baseUrl = json.optString("baseUrl").ifBlank { null },
            pin = json.optString("pin").ifBlank { null },
            margemVminTopo = json.margemVmin("margemVminTopo"),
            margemVminBase = json.margemVmin("margemVminBase"),
            margemVminEsquerda = json.margemVmin("margemVminEsquerda"),
            margemVminDireita = json.margemVmin("margemVminDireita"),
            // Só 0/90/180/270 — qualquer outra coisa (string, número fora do
            // conjunto) vira null aqui, e ConfigAparelho.rotacaoTela também
            // barra de novo na escrita. Duas guardas, mesma regra.
            rotacaoTela = if (json.has("rotacaoTela") && !json.isNull("rotacaoTela")) {
                json.optInt("rotacaoTela", -1).takeIf { it in ConfigAparelho.ROTACOES_VALIDAS }
            } else {
                null
            },
        )
    }.getOrNull()

    private fun JSONObject.margemVmin(chave: String): Float? =
        if (has(chave) && !isNull(chave)) {
            // optDouble devolve NaN se o valor não for numérico (ex.: uma
            // string) — nunca propaga isso pra frente: NaN sobrevive a
            // coerceIn() sem ser pego (NaN < x e NaN > x são sempre
            // falsos) e vira padding silenciosamente zerado lá na frente.
            optDouble(chave).toFloat().takeUnless { it.isNaN() }
        } else {
            null
        }

    fun procurarEAplicar(context: Context, config: ConfigAparelho) {
        if (config.provisionado) return

        val arquivo = localizarArquivo(context)
        if (arquivo == null) {
            Log.i(TAG, "$NOME_ARQUIVO não encontrado em nenhum volume montado")
            return
        }

        val texto = runCatching { arquivo.readText() }.getOrNull()
        if (texto == null) {
            Log.w(TAG, "${arquivo.absolutePath} encontrado, mas não pôde ser lido")
            return
        }

        val dados = parse(texto)
        if (dados == null) {
            Log.w(TAG, "${arquivo.absolutePath} encontrado, mas não é JSON válido")
            return
        }

        // O mesmo token que já está gravado e ainda não virou credencial:
        // reaplicar não adianta. Mas um token DIFERENTE, ou credencial
        // completa, é o técnico trazendo um pendrive novo porque o anterior
        // não serviu (expirou, ou foi queimado sem a resposta chegar). Antes,
        // qualquer token gravado fazia o pendrive ser ignorado para sempre, e
        // a TV só voltava limpando os dados do app (BUG-033).
        val tokenGravado = config.tokenProvisionamento
        if (!tokenGravado.isNullOrBlank() && dados.tokenProvisionamento == tokenGravado && dados.chaveAparelho == null) {
            Log.i(TAG, "mesmo token do pendrive já gravado; aguardando a troca")
            return
        }

        aplicar(dados, config)
        Log.i(TAG, "configuração aplicada a partir de ${arquivo.absolutePath}")
    }

    /** Procura em todo volume de armazenamento montado, incluindo o pendrive USB. */
    private fun localizarArquivo(context: Context): File? {
        val candidatos = mutableListOf<File>()

        candidatos += File(Environment.getExternalStorageDirectory(), NOME_ARQUIVO)

        // getExternalFilesDirs devolve um diretório específico do app por
        // volume montado (inclusive o pendrive, quando o sistema o monta como
        // armazenamento removível) — a raiz do volume é o que vem antes de
        // "/Android/data/...".
        context.getExternalFilesDirs(null).forEach { dirEspecificoDoApp ->
            val raiz = dirEspecificoDoApp?.let(::raizDoVolume) ?: return@forEach
            candidatos += File(raiz, NOME_ARQUIVO)
        }

        return candidatos.firstOrNull { it.exists() && it.canRead() }
    }

    private fun raizDoVolume(dirEspecificoDoApp: File): File? {
        val caminho = dirEspecificoDoApp.absolutePath
        val indice = caminho.indexOf(MARCADOR_ANDROID_DATA)
        if (indice < 0) return null
        return File(caminho.substring(0, indice))
    }

    private fun aplicar(dados: Dados, config: ConfigAparelho) {
        dados.dispositivoId?.let { config.dispositivoId = it }
        dados.chaveAparelho?.let { config.chaveAparelho = it }
        // Guardado, não trocado aqui: a troca por credencial exige rede, e
        // este método roda em onCreate. Quem resolve é SincronizacaoV2.
        dados.tokenProvisionamento?.let { config.tokenProvisionamento = it }
        dados.baseUrl?.let { config.baseUrl = it }
        dados.pin?.let { config.pinPainel = it }
        dados.margemVminTopo?.let { config.margemVminTopo = it }
        dados.margemVminBase?.let { config.margemVminBase = it }
        dados.margemVminEsquerda?.let { config.margemVminEsquerda = it }
        dados.margemVminDireita?.let { config.margemVminDireita = it }
        dados.rotacaoTela?.let { config.rotacaoTela = it }
    }

    private const val TAG = "ConfigExterna"
    private const val MARCADOR_ANDROID_DATA = "/Android/data/"
}
