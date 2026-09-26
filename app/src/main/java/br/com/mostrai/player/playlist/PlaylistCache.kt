package br.com.mostrai.player.playlist

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

/**
 * Última playlist recebida com sucesso, guardada para tocar offline.
 *
 * Guarda também a âncora de tempo ([RelogioJanela]) que veio junto — sem
 * ela, uma retomada por posição temporal não é possível, e o app não deve
 * inventar uma (começa do primeiro item em vez disso).
 */
class PlaylistCache(context: Context) {

    private val resolver = context.applicationContext.contentResolver
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    fun salvar(corpoBruto: String, ancora: RelogioJanela?) {
        prefs.edit()
            .putString(CHAVE_CORPO, corpoBruto)
            .putLong(CHAVE_ANCORA_SERVIDOR, ancora?.servidorAgoraEpochMs ?: -1L)
            .putLong(CHAVE_ANCORA_ELAPSED, ancora?.elapsedRealtimeNaAncoraMs ?: -1L)
            .putInt(CHAVE_ANCORA_BOOT, contagemDeBoot())
            .apply()
    }

    fun limpar() {
        prefs.edit().clear().apply()
    }

    fun carregar(): Salva? {
        val corpo = prefs.getString(CHAVE_CORPO, null) ?: return null
        val servidorMs = prefs.getLong(CHAVE_ANCORA_SERVIDOR, -1L)
        val elapsedMs = prefs.getLong(CHAVE_ANCORA_ELAPSED, -1L)
        val ancora = if (servidorMs >= 0 && elapsedMs >= 0 && mesmoBoot()) RelogioJanela(servidorMs, elapsedMs) else null
        return Salva(corpo, ancora)
    }

    /**
     * [RelogioJanela.valida] só percebe o reboot enquanto o uptime atual é
     * menor que o da âncora (BUG-016). A TV que desliga toda noite passa
     * desse uptime no meio do dia seguinte, e a âncora de ontem voltava a
     * parecer válida — projetando o horário do servidor sem contar a noite
     * desligada. O contador de boot do sistema fecha o buraco; sem ele
     * (-1), fica a verificação antiga.
     */
    private fun mesmoBoot(): Boolean {
        val salvo = prefs.getInt(CHAVE_ANCORA_BOOT, -1)
        val atual = contagemDeBoot()
        return salvo < 0 || atual < 0 || salvo == atual
    }

    private fun contagemDeBoot(): Int =
        runCatching { Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    data class Salva(val corpoBruto: String, val ancora: RelogioJanela?)

    private companion object {
        const val ARQUIVO = "mostrai_cache_playlist"
        const val CHAVE_CORPO = "corpo_bruto"
        const val CHAVE_ANCORA_SERVIDOR = "ancora_servidor_ms"
        const val CHAVE_ANCORA_ELAPSED = "ancora_elapsed_ms"
        const val CHAVE_ANCORA_BOOT = "ancora_boot"
    }
}
