# Fila de comprovantes e cache confiavam no relógio de parede e no disco de antes

**Data:** 05/10/2026 · **Achado em:** revisão de 02/10 (plausíveis a, b, c),
reproduzidos e corrigidos na finalização V1 · **Severidade:** média
(comprovante faturável perdido ou preso; disco cheio sob a fila)

## O que acontecia

1. **Espera de reenvio presa por anos.** O backoff grava
   `proximo_envio_em` no relógio de parede. TV que liga com o relógio
   adiantado (sem bateria no RTC) e depois é corrigida pelo NTP: o
   comprovante esperava a vez numa data que só chega anos depois.
2. **Exibição no ar apagada pela limpeza.** Órfão (linha sem
   `terminado_em`) sai depois de 7 dias pelo relógio de parede — e a
   exibição em andamento também não tem `terminado_em`. Relógio que salta
   dias para frente no meio de um anúncio: a linha sumia, e o fim da
   exibição não tinha mais o que marcar.
3. **Fila cheia descartava a exibição no ar** pelo mesmo motivo (órfão sai
   primeiro).
4. **Comprovante carimbado com o relógio da TV** (`iniciadoEm`/
   `terminadoEm`), mesmo com o relógio do servidor disponível.
5. **Download comia a reserva de disco**: a checagem era só antes de
   começar; um vídeo grande baixava inteiro por cima da reserva que existe
   para o sistema e a fila de comprovantes.

## Correção

- Espera marcada mais de 31 min à frente (o maior degrau do backoff é
  30 min) é tratada como relógio que voltou: o comprovante sai.
  `ProofOfPlayDb.elegiveisParaEnvio`.
- Exibições em andamento deste processo ficam protegidas da limpeza e do
  descarte (`FilaProofOfPlay.emAndamento`).
- Carimbo no relógio confiável (`FilaProofOfPlay(relogio = …)`).
- Reserva conferida pelo `Content-Length` antes e a cada 8 MB durante o
  download; atingida, para e apaga o parcial (`CacheMidia`).
- Testes: `FilaRelogioHostilTest` (5), `CacheReservaTest` (3).

## Regra que fica

Tudo que a TV decide por tempo usa o relógio confiável ou o monotônico;
quando só há o de parede, o código supõe que ele salta nos dois sentidos.
