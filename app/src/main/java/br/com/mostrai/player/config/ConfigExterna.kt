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
        val baseUrl: String? = null,
        val pin: String? = null,
        val margemVmin: Float? = null,
    )

    /** Função pura — testável sem Android, sem arquivo, sem permissão. */
    fun parse(textoJson: String): Dados? = runCatching {
        val json = JSONObject(textoJson)
        Dados(
            dispositivoId = json.optString("dispositivoId").ifBlank { null },
            chaveAparelho = json.optString("chaveAparelho").ifBlank { null },
            baseUrl = json.optString("baseUrl").ifBlank { null },
            pin = json.optString("pin").ifBlank { null },
            margemVmin = if (json.has("margemVmin") && !json.isNull("margemVmin")) {
                // optDouble devolve NaN se o valor não for numérico (ex.: uma
                // string) — nunca propaga isso pra frente: NaN sobrevive a
                // coerceIn() sem ser pego (NaN < x e NaN > x são sempre
                // falsos) e vira padding silenciosamente zerado lá na frente.
                json.optDouble("margemVmin").toFloat().takeUnless { it.isNaN() }
            } else {
                null
            },
        )
    }.getOrNull()

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
        dados.baseUrl?.let { config.baseUrl = it }
        dados.pin?.let { config.pinPainel = it }
        dados.margemVmin?.let { config.margemVmin = it }
    }

    private const val TAG = "ConfigExterna"
    private const val MARCADOR_ANDROID_DATA = "/Android/data/"
}
