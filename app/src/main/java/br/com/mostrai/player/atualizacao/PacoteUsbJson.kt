package br.com.mostrai.player.atualizacao

import org.json.JSONException
import org.json.JSONObject

/**
 * `MOSTRAI/update/update.json` do pendrive (gerado por
 * `scripts/preparar-usb-update.sh`). **Não é fonte de confiança**: o APK é
 * conferido de verdade (pacote, assinatura, versão). O JSON só serve para
 * pegar erro de empacotamento — se ele diz um SHA-256 e o APK não bate, o
 * pacote está corrompido ou trocado. Todos os campos são opcionais.
 */
data class PacoteUsbJson(
    val versionName: String?,
    val versionCode: Long?,
    val sha256: String?,
    val certificateSha256: String?,
) {
    companion object {
        /** Teto do update.json: é um punhado de campos. */
        const val TAMANHO_MAXIMO = 64 * 1024

        /** `null` = JSON ilegível (o pacote é recusado — empacotamento quebrado). */
        fun parse(texto: String): PacoteUsbJson? = try {
            val o = JSONObject(texto)
            PacoteUsbJson(
                versionName = o.optString("versionName").takeIf { it.isNotBlank() },
                versionCode = if (o.has("versionCode")) o.optLong("versionCode", -1L).takeIf { it >= 0 } else null,
                sha256 = o.optString("sha256").trim().lowercase().takeIf { it.isNotEmpty() },
                certificateSha256 = o.optString("certificateSha256").trim().lowercase().takeIf { it.isNotEmpty() },
            )
        } catch (_: JSONException) {
            null
        }
    }
}
