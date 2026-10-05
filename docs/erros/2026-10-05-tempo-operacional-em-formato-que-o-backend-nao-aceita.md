# Tempo operacional ia num formato que o backend publicado não aceita

**Data:** 05/10/2026 · **Achado em:** investigação da finalização V1
(leitura do `main` do backend depois do #114) · **Severidade:** alta
(tempo operacional do Ponto Móvel — base da hospedagem — nunca seria
registrado)

## O que acontecia

O Player 02/10 mandava **sessões** (`{sessoes: [{sessaoId, bootCount,
inicioUptimeMs, …, motivo}]}`), o formato do rascunho
[sancompany/MostrAi#113](https://github.com/sancompany/MostrAi/pull/113).
O backend que foi a produção é o do
[#114](https://github.com/sancompany/MostrAi/pull/114), que espera
**segmentos** (`{segmentos: [{bootId, seq, inicio, fim}]}`, no relógio do
servidor, ≤ 6 h, idempotente por (tela, boot, seq)). Nenhum campo batia:
toda sessão seria `item_invalido` — ou ficaria guardada para sempre na TV
esperando um `registrada` que nunca viria.

Junto: o institucional de reserva mandava `NO_PLAYLIST` no heartbeat, que o
#114 trata como **erro** — a tela no ar com o vídeo da Mostraí apareceria
como falha, e o tempo dela não contaria como operação.

E a documentação de três lugares discordava do intervalo do heartbeat (15 s
no Player desde a 2.0.0; 5 min e tolerância de 6,5 min no backend). A
investigação do APK de campo mostrou que 0.1.0/1.0.0 batiam a cada 5 min e
a 2.0.0 a cada 15 s.

## Por que passou

O contrato foi implementado contra um PR em rascunho, e a matriz
contrato × Player marcou MATCH contra esse rascunho. Quando o desenho mudou
no backend (#114) e o #113 foi abandonado, nada no Player relia o `main`.

## Correção

- `RegistroOperacional` reescrito para segmentos: `bootId` =
  `b<BOOT_COUNT>.<aleatório>`, `seq` que nunca se repete, monotônico do
  boot, âncora `servidorAgora` por boot; sem âncora espera; boot que acabou
  sem âncora é descartado e contado (`OPERACAO_SEM_ANCORA`).
  `OperacaoJson`, `MostraiApi.enviarOperacao`.
- Institucional de reserva = `IDLE`; `NO_PLAYLIST` só sem nada guardado.
- Heartbeat de 15 s como fonte única; backend alinhado em
  [sancompany/MostrAi#115](https://github.com/sancompany/MostrAi/pull/115).
- Testes: `OfflinePecasTest` (9 de segmentos), `PontoMovelCicloTest`
  (formato do corpo, só sai com `ok`, 404 guarda e não insiste, `IDLE`).

## Regra que fica

A matriz contrato × Player só vale contra o **`main`** do backend (o que
está publicado), com o commit anotado. PR em rascunho é proposta, não
contrato.
