package br.com.mostrai.player.ui

import android.os.SystemClock
import android.view.KeyEvent

/**
 * Gesto de abertura do painel de manutenção no controle remoto.
 *
 * Equivalente aos 5 toques num canto usados hoje no player web: cinco
 * acionamentos do botão OK/CENTER dentro de [JANELA_MS]. Usa relógio monotônico
 * — o relógio da TV não é confiável.
 */
class GestoPainel(private val aoCompletar: () -> Unit) {

    private var contagem = 0
    private var primeiroEm = 0L

    fun aoTeclar(codigo: Int): Boolean {
        if (codigo != KeyEvent.KEYCODE_DPAD_CENTER && codigo != KeyEvent.KEYCODE_ENTER) {
            contagem = 0
            return false
        }

        val agora = SystemClock.elapsedRealtime()
        if (contagem == 0 || agora - primeiroEm > JANELA_MS) {
            contagem = 1
            primeiroEm = agora
            return true
        }

        contagem++
        if (contagem >= TOQUES) {
            contagem = 0
            aoCompletar()
        }
        return true
    }

    companion object {
        const val TOQUES = 5
        const val JANELA_MS = 3_000L
    }
}
