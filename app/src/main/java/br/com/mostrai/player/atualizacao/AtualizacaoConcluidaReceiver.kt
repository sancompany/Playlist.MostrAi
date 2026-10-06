package br.com.mostrai.player.atualizacao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import br.com.mostrai.player.BuildConfig
import br.com.mostrai.player.estado.DiarioBordo
import br.com.mostrai.player.kiosk.Watchdog
import java.io.File
import java.time.Instant

/**
 * O Android acabou de trocar o APK (`MY_PACKAGE_REPLACED`) — este código já
 * é a versão nova. Credencial, ID da tela, preferências, bancos (fila de
 * comprovantes, tempo operacional, diário) e cache moram na área do app e
 * sobrevivem à troca, porque a assinatura é a mesma: não há o que migrar.
 * Aqui só se apaga a cópia do APK, registra-se a troca e o Player volta à
 * frente (no Android 10+, se "Exibir sobre outros apps" deixar).
 */
class AtualizacaoConcluidaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        File(context.filesDir, DIRETORIO).listFiles()?.forEach { it.delete() }
        val descricao = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) em ${Instant.now()}"
        EstadoAtualizacao.registrarAtualizado(context, descricao)
        runCatching { DiarioBordo(context).registrar(DiarioBordo.Codigo.ATUALIZADO, "versão ${BuildConfig.VERSION_NAME}") }
        Watchdog.rearmar(context)
        Watchdog.abrirPlayer(context)
    }

    companion object {
        /** Pasta privada (`filesDir`) da cópia do APK — a mesma do `FileProvider`. */
        const val DIRETORIO = "atualizacao"
    }
}
