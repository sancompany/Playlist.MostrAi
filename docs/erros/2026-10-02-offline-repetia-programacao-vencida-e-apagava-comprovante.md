# Offline repetia programação vencida e apagava comprovante por idade

**Data:** 02/10/2026 · **Achado em:** pedido do dono "Master Player — cache e
operação offline prolongada para pontos móveis" · **Severidade:** alta
(veiculação inventada e evidência faturável perdida)

## O que acontecia

Uma TV de ponto móvel passa dias sem internet. O Player:

1. **Repetia a última playlist para sempre.** A regra era "na virada da hora
   sem rede, segue a última que tinha" (contrato §7, RN-07). Doze horas
   depois, a TV ainda exibia — e comprovava — os anúncios das 10 h, que o
   servidor nunca programou para aquela hora. Comprovante fora da janela é
   recusado; exibição fora da janela é veiculação inventada.
2. **Confiava no relógio da TV depois de um reboot sem rede.** Sem RTC, a
   SEMP volta do reboot com a hora errada; a âncora do servidor se perde no
   reboot. A posição na hora e a validade da janela saíam desse relógio.
3. **Apagava comprovante pendente com mais de 7 dias + 1 h.** Pensado como
   "o servidor não aceita mais", mas medido pelo relógio de parede da TV
   (que pode estar adiantado) e cego ao caso do servidor que aceita com
   status final. Uma TV que voltasse no 8.º dia perdia tudo antes de
   perguntar.
4. **Quarentenava o lote inteiro por 400 sem prova.** Um servidor
   recusando tudo (bug do lado de lá) tirava da fila, um a um, todos os
   comprovantes.
5. **Guardava o cache em `cacheDir`**, que o Android limpa sozinho quando
   falta espaço — justamente as mídias da programação offline.

## Correção

- Comercial só dentro de `janelaFim` (sem ele, `janelaInicio` + 1 h);
  depois, só o institucional guardado (`InstitucionalLocal`), sem
  comprovante. `Playlist.comercialAutorizadoEm`.
- `RelogioConfiavel`: âncora do servidor; sem âncora, relógio da TV só se
  não estiver atrás do último instante do servidor ("piso"); senão, nenhum
  comercial.
- Pendente fica até o ACK; só órfão e quarentena expiram.
  `FilaProofOfPlay.removerSemValor`.
- Quarentena só com prova de irmão aceito; senão, espera crescente e
  `FILA_RECUSADA` no diário. `FilaProofOfPlay.Bissecao`.
- Cache em `filesDir/midia`, chaves protegidas, reserva de disco.

Testes: `PontoMovelCicloTest`, `OfflinePecasTest`, `FilaPerdasTest`,
`FilaEscalaEQuarentenaTest`, `CacheMidiaHashTest`.

## Para não repetir

"Offline não para a tela" virou "offline não autoriza inventar veiculação".
Toda regra de offline precisa responder: **o servidor programou isto para
esta hora?** Se não dá para saber, não é comercial.
