package br.com.mostrai.player.ui

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * O bloco técnico que aparece no pedido de PIN — nunca na tela pública
 * (Ponto Móvel, 02/10/2026). Responde, para quem vai tirar a tela da base:
 * "dá para levar esta TV sem internet, e até quando?". Puro, para teste.
 *
 * "Pronto para offline" exige: aparelho instalado, programação autorizada
 * agora, toda a mídia dela já no disco e espaço livre acima da reserva. A
 * validade é o fim da janela que o servidor autorizou — no contrato atual,
 * uma hora; um pacote de vários dias depende do backend (docs/offline-prolongado.md).
 */
object InfoSuporte {

    data class Dados(
        val dispositivoId: String?,
        val versao: String,
        val online: Boolean,
        val ultimaSincronizacaoMs: Long?,
        val programacaoValidaAteMs: Long?,
        val programacaoAutorizadaAgora: Boolean,
        val midiaEmCache: Int,
        val midiaTotal: Int,
        val institucionalGuardado: Int,
        val espacoSuficiente: Boolean,
        val comprovantesPendentes: Int,
        val sessoesPendentes: Int,
        val tempoOperacionalPendenteMs: Long,
        val offsetLocal: ZoneOffset = ZoneOffset.ofHours(-3),
    )

    fun motivosNaoPronto(d: Dados): List<String> = buildList {
        if (d.dispositivoId == null) add("aparelho não instalado")
        if (!d.programacaoAutorizadaAgora) add("sem programação válida")
        if (d.midiaEmCache < d.midiaTotal) add("mídia incompleta (${d.midiaEmCache}/${d.midiaTotal})")
        if (!d.espacoSuficiente) add("pouco espaço em disco")
    }

    fun texto(d: Dados): String {
        val formato = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(d.offsetLocal)
        fun quando(ms: Long?) = ms?.let { formato.format(Instant.ofEpochMilli(it)) } ?: "nunca"
        val motivos = motivosNaoPronto(d)
        val prontidao = if (motivos.isEmpty()) {
            "PRONTO PARA OFFLINE ATÉ ${quando(d.programacaoValidaAteMs)}"
        } else {
            "NÃO PRONTO PARA OFFLINE: ${motivos.joinToString("; ")}"
        }
        return listOf(
            "Tela ${d.dispositivoId ?: "—"} · versão ${d.versao}",
            "Conexão: ${if (d.online) "online" else "offline"}",
            "Última sincronização: ${quando(d.ultimaSincronizacaoMs)}",
            "Programação válida até: ${if (d.programacaoValidaAteMs == null) "—" else quando(d.programacaoValidaAteMs)}",
            "Mídia: ${d.midiaEmCache}/${d.midiaTotal} no aparelho · institucional: ${d.institucionalGuardado}",
            "Comprovantes aguardando envio: ${d.comprovantesPendentes}",
            "Tempo operacional aguardando envio: ${duracao(d.tempoOperacionalPendenteMs)} (${d.sessoesPendentes} sessões)",
            prontidao,
        ).joinToString("\n")
    }

    private fun duracao(ms: Long): String {
        val minutos = ms / 60_000
        return if (minutos < 60) "$minutos min" else "${minutos / 60} h ${minutos % 60} min"
    }
}
