package br.com.mostrai.player.network

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import br.com.mostrai.player.BuildConfig
import java.util.TimeZone
import org.json.JSONObject

/**
 * `POST /player/:dispositivoId/hello` — os dados técnicos que não mudam.
 *
 * Existe para tirar peso do heartbeat: fabricante, modelo, versão do Android
 * e resolução são constantes durante toda a vida do aparelho, e repeti-los a
 * cada 5 minutos seria mandar o mesmo texto ~105 mil vezes por ano por tela.
 * Vai no boot, depois de um provisionamento, e quando algum desses valores
 * realmente mudar (atualização do app ou do firmware).
 *
 * Se o backend ainda não conhece a rota, responde 404 e o player apenas
 * marca o V2 como indisponível — nada quebra.
 */
object HelloJson {

    data class Dados(
        val versaoApp: String,
        val buildNumber: Int,
        val fabricante: String,
        val modelo: String,
        val android: String,
        val largura: Int,
        val altura: Int,
        val timezone: String,
    ) {
        /**
         * Identidade do conjunto: se mudar, vale reenviar o hello. Evita
         * tanto o silêncio (firmware novo nunca reportado) quanto o
         * desperdício (reenviar igual todo boot).
         */
        fun assinatura(): String =
            "$versaoApp|$buildNumber|$fabricante|$modelo|$android|$largura|$altura|$timezone"
    }

    @Suppress("DEPRECATION")
    fun coletar(context: Context): Dados {
        val metrics = DisplayMetrics()
        val janelas = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        janelas?.defaultDisplay?.getRealMetrics(metrics)

        return Dados(
            versaoApp = BuildConfig.VERSION_NAME,
            buildNumber = BuildConfig.VERSION_CODE,
            fabricante = Build.MANUFACTURER ?: "",
            modelo = Build.MODEL ?: "",
            android = Build.VERSION.RELEASE ?: "",
            largura = metrics.widthPixels,
            altura = metrics.heightPixels,
            timezone = TimeZone.getDefault().id,
        )
    }

    fun corpo(dados: Dados): String = JSONObject().apply {
        put("contrato", HeartbeatJson.CONTRATO)
        put("versaoApp", dados.versaoApp)
        put("buildNumber", dados.buildNumber)
        put("fabricante", dados.fabricante)
        put("modelo", dados.modelo)
        put("android", dados.android)
        put("largura", dados.largura)
        put("altura", dados.altura)
        put("timezone", dados.timezone)
    }.toString()

    /** O hello já pode trazer a versão de config vigente, poupando um ciclo. */
    fun parseConfigVersion(corpoBruto: String): Int? = runCatching {
        val json = JSONObject(corpoBruto)
        if (json.has("configVersion") && !json.isNull("configVersion")) {
            json.optInt("configVersion", -1).takeIf { it >= 0 }
        } else {
            null
        }
    }.getOrNull()
}
