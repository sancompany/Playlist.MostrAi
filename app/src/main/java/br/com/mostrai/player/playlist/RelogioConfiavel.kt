package br.com.mostrai.player.playlist

import android.content.Context

/**
 * Que horas são — sem confiar cegamente no relógio da TV.
 *
 * Ordem de confiança:
 * 1. **Âncora do servidor** ([RelogioJanela] válida): `servidorAgora` + tempo
 *    monotônico desde a resposta. Imune a relógio errado, fuso e ajuste à mão.
 * 2. **Relógio da TV, só se não estiver atrás do piso.** O piso é o maior
 *    instante do servidor que o Player já viu passar (gravado a cada minuto
 *    enquanto há âncora). Depois de um reboot sem internet, a âncora some; um
 *    relógio de parede que marca ANTES do piso está comprovadamente errado
 *    (TV sem RTC volta para a data do firmware, ajuste à mão errado).
 * 3. **Não sei** (`null`). Quem chama decide o lado seguro: a programação
 *    comercial não toca (offline não autoriza veiculação), e o horário do
 *    ponto fica no padrão permissivo.
 *
 * O piso só sobe com a âncora — nunca com o relógio de parede, senão um
 * relógio adiantado em dias empurraria o piso para o futuro e travaria a
 * operação offline até o tempo real alcançá-lo.
 *
 * Risco aceito: depois de um reboot offline, um relógio de parede atrasado
 * menos do que o tempo em que a TV ficou desligada passa pela guarda. O
 * estrago é limitado à janela já autorizada (uma hora no contrato atual).
 */
class RelogioConfiavel(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    fun agoraMs(ancora: RelogioJanela?, paredeMs: Long = System.currentTimeMillis()): Long? =
        escolher(ancora?.takeIf { it.valida() }?.agoraDoServidorMs(), paredeMs, pisoMs())

    /** Sobe o piso até o "agora" da âncora. Barato: chamado a cada minuto. */
    fun registrarPiso(ancora: RelogioJanela?) {
        val agora = ancora?.takeIf { it.valida() }?.agoraDoServidorMs() ?: return
        if (agora > pisoMs()) prefs.edit().putLong(CHAVE_PISO, agora).apply()
    }

    fun pisoMs(): Long = prefs.getLong(CHAVE_PISO, 0L)

    /** Última playlist recebida do servidor — para o bloco técnico. */
    fun registrarSincronizacao(agoraMs: Long?) {
        prefs.edit().putLong(CHAVE_SINCRONIZACAO, agoraMs ?: System.currentTimeMillis()).apply()
    }

    fun ultimaSincronizacaoMs(): Long? = prefs.getLong(CHAVE_SINCRONIZACAO, 0L).takeIf { it > 0L }

    companion object {
        const val ARQUIVO = "mostrai_relogio"
        private const val CHAVE_PISO = "piso_servidor_ms"
        private const val CHAVE_SINCRONIZACAO = "ultima_sincronizacao_ms"

        /** Pura, para teste: âncora vence; parede só vale se não estiver atrás do piso. */
        fun escolher(ancoraMs: Long?, paredeMs: Long, pisoMs: Long): Long? = when {
            ancoraMs != null -> ancoraMs
            pisoMs > 0L && paredeMs >= pisoMs -> paredeMs
            else -> null
        }
    }
}
