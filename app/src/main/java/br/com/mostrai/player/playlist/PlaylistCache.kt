package br.com.mostrai.player.playlist

import android.content.Context
import android.content.SharedPreferences

/**
 * Última playlist recebida com sucesso, guardada para tocar offline.
 *
 * Guarda também a âncora de tempo ([RelogioJanela]) que veio junto — sem
 * ela, uma retomada por posição temporal não é possível, e o app não deve
 * inventar uma (cai para a tela institucional em vez disso).
 */
class PlaylistCache(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    fun salvar(corpoBruto: String, ancora: RelogioJanela?) {
        prefs.edit()
            .putString(CHAVE_CORPO, corpoBruto)
            .putLong(CHAVE_ANCORA_SERVIDOR, ancora?.servidorAgoraEpochMs ?: -1L)
            .putLong(CHAVE_ANCORA_ELAPSED, ancora?.elapsedRealtimeNaAncoraMs ?: -1L)
            .apply()
    }

    fun carregar(): Salva? {
        val corpo = prefs.getString(CHAVE_CORPO, null) ?: return null
        val servidorMs = prefs.getLong(CHAVE_ANCORA_SERVIDOR, -1L)
        val elapsedMs = prefs.getLong(CHAVE_ANCORA_ELAPSED, -1L)
        val ancora = if (servidorMs >= 0 && elapsedMs >= 0) RelogioJanela(servidorMs, elapsedMs) else null
        return Salva(corpo, ancora)
    }

    data class Salva(val corpoBruto: String, val ancora: RelogioJanela?)

    private companion object {
        const val ARQUIVO = "mostrai_cache_playlist"
        const val CHAVE_CORPO = "corpo_bruto"
        const val CHAVE_ANCORA_SERVIDOR = "ancora_servidor_ms"
        const val CHAVE_ANCORA_ELAPSED = "ancora_elapsed_ms"
    }
}
