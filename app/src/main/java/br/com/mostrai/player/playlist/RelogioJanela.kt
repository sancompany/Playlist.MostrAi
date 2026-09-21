package br.com.mostrai.player.playlist

import android.os.SystemClock
import java.time.OffsetDateTime

/**
 * Ancora o instante do servidor (`servidorAgora`) ao tempo monotônico local no
 * momento em que a resposta chegou. A partir daí, qualquer "agora" é
 * calculado por [SystemClock.elapsedRealtime] — nunca pelo relógio de parede
 * do aparelho, que não é confiável.
 *
 * A âncora só continua válida enquanto o processo não passou por um reboot
 * real: um reboot zera o relógio monotônico, e isso é detectável (ver
 * [valida]). Os campos são públicos só para permitir persistir a âncora em
 * [PlaylistCache]; nada fora deste pacote deve construí-la sem vir de
 * [agora].
 */
data class RelogioJanela(
    val servidorAgoraEpochMs: Long,
    val elapsedRealtimeNaAncoraMs: Long,
) {
    /** Agora estimado no relógio do servidor, projetado a partir da âncora. */
    fun agoraDoServidorMs(): Long {
        val decorrido = SystemClock.elapsedRealtime() - elapsedRealtimeNaAncoraMs
        return servidorAgoraEpochMs + decorrido
    }

    /**
     * Falso quando o relógio monotônico local não pode mais ser relacionado
     * a esta âncora — sinal de que o aparelho passou por um reboot real
     * desde que ela foi criada (o relógio monotônico reinicia do zero, então
     * ele nunca pode estar "atrás" de uma âncora legítima). Sem uma âncora
     * válida, o app não deve tentar retomar posição nenhuma: precisa esperar
     * um `servidorAgora` novo (decisão fechada com o GPT, item 7.1).
     */
    fun valida(): Boolean = SystemClock.elapsedRealtime() >= elapsedRealtimeNaAncoraMs

    companion object {
        fun agora(servidorAgoraIso: String): RelogioJanela? {
            val instante = runCatching { OffsetDateTime.parse(servidorAgoraIso) }.getOrNull() ?: return null
            return RelogioJanela(
                servidorAgoraEpochMs = instante.toInstant().toEpochMilli(),
                elapsedRealtimeNaAncoraMs = SystemClock.elapsedRealtime(),
            )
        }
    }
}
