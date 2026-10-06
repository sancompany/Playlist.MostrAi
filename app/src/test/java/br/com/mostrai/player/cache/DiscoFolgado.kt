package br.com.mostrai.player.cache

import org.junit.rules.ExternalResource

/**
 * Disco fixo e folgado (100 GB, 50 livres) enquanto o teste roda: a reserva
 * do cache (maior entre 512 MB e 10 % do disco) não pode depender de quanto
 * espaço sobrou na máquina que roda a suíte.
 */
class DiscoFolgado : ExternalResource() {
    override fun before() {
        MedidorDeDisco.livre = { 50L shl 30 }
        MedidorDeDisco.total = { 100L shl 30 }
    }

    override fun after() = MedidorDeDisco.padrao()
}
