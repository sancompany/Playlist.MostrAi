package br.com.mostrai.player.playlist

/**
 * Calcula em que item da playlist a reprodução deveria estar, dado quanto já
 * passou da janela — nunca pelo relógio da TV, pelo tempo do servidor
 * projetado por [RelogioJanela].
 *
 * Decisão fechada com o GPT (item 7.1): reentrada é sempre por posição
 * temporal, nunca por retomar o último índice tocado — o índice deixa de ser
 * estado importante, vira consequência calculada da posição na janela. Um
 * item pego no meio é pulado inteiro: nunca há seek para o meio de um vídeo,
 * porque uma exibição parcial não pode virar comprovante (decisão 2, seção
 * 4 — só STATE_ENDED conta).
 */
object PosicaoNaPlaylist {

    /**
     * Tolerância na borda inicial de um item: pequenas diferenças de
     * sincronização (100–200ms) não podem descartar a peça inteira.
     */
    private const val TOLERANCIA_MS = 500L

    fun calcular(itens: List<ItemPlaylist>, decorridoNaJanelaMs: Long): Int {
        if (itens.isEmpty()) return 0
        if (decorridoNaJanelaMs < 0) return 0

        var inicioMs = 0L
        for ((indice, item) in itens.withIndex()) {
            val fimMs = inicioMs + item.duracaoSegundos * 1000L

            if (decorridoNaJanelaMs < inicioMs + TOLERANCIA_MS) {
                // Ainda não chegou aqui, ou chegou dentro da tolerância inicial
                // deste item: toca este item inteiro, desde o começo.
                return indice
            }
            if (decorridoNaJanelaMs < fimMs) {
                // Está no meio deste item. Não faz seek: pula para o próximo.
                return if (indice + 1 < itens.size) indice + 1 else itens.lastIndex
            }
            inicioMs = fimMs
        }
        // Passou do fim da janela conhecida (ex.: playlist desatualizada) —
        // fica no último item até a próxima chegar.
        return itens.lastIndex
    }
}
