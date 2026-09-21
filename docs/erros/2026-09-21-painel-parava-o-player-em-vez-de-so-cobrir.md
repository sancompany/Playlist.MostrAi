# Abrir o painel de manutenção parava o player, não só cobria a tela

**Marcar como de ecossistema** — o padrão (documentar um comportamento de
sobreposição — "a tela de baixo continua rodando" — sem o item de tema que
o Android exige pra isso de verdade, `windowIsTranslucent`) não é
específico deste projeto: qualquer app com uma segunda Activity pensada
como "painel por cima" tem o mesmo risco se a translucidez não for
declarada explicitamente no tema — o layout pode até *parecer* uma
sobreposição (fundo com alfa) sem a janela ser uma de verdade.

## O que aconteceu

`docs/funcional.md`, jornada do operador, item 5, sempre documentou:
"`VOLTAR` → fecha o painel, volta à reprodução normal sem interromper o
vídeo em andamento (o player continua rodando por trás)." E
`activity_painel.xml` sempre teve `android:background="#E6000000"` no
`FrameLayout` raiz — preto com ~90% de opacidade, um valor que só faz
sentido visualmente se alguma coisa aparecer through os 10% restantes.

Mas nem `Theme.MostraiPlayer` (usado pelas duas Activities) nem
`PainelActivity` no manifesto declaravam `android:windowIsTranslucent`.
Sem isso, abrir `PainelActivity` é uma troca de Activity **opaca** como
qualquer outra — o Android chama `PlayerActivity.onStop()`, que:

- libera o ExoPlayer inteiro (`liberarPlayer()`) — a exibição em
  andamento para, sem `STATE_ENDED`, sem virar comprovante;
- cancela todos os temporizadores (`handler.removeCallbacksAndMessages(null)`)
  — poll de playlist, heartbeat, flush da fila de proof-of-play, virada de
  hora — tudo pausa enquanto o painel está aberto.

Ao voltar (`VOLTAR`), `onStart()` recria o player do zero e busca a
playlist de novo; a reentrada por posição temporal (decisão 7.1) recalcula
onde deveria estar, mas "nunca há seek" (RN-05) — se a volta cai no meio
do item que estava tocando, ele é pulado inteiro, não retomado. Ou seja: o
operador que abre o painel pra checar o estado da tela **interrompe** uma
exibição paga no processo — o oposto do que a própria documentação do
projeto sempre disse que aconteceria.

## A regra que isso gerou

Um valor de opacidade parcial num layout (`#E6000000`, não `#FF000000`) é
uma pista de intenção — se o design pede "por cima, não em vez de", o tema
da Activity precisa declarar `windowIsTranslucent` de verdade; a cor
sozinha não faz o Android tratar a Activity de baixo como ainda visível.
Documentar o comportamento pretendido (`docs/funcional.md`) sem verificar
se o código realmente produz esse comportamento deixa a lacuna invisível
até alguém ler o dois lado a lado.

## Onde foi corrigido

Novo tema `Theme.MostraiPlayer.Translucido` (`themes.xml`), com
`windowIsTranslucent=true` e `windowBackground=transparent`, aplicado só a
`PainelActivity` no manifesto — `PlayerActivity` continua com o tema opaco
de sempre, como base da pilha. Com isso, `PlayerActivity` só pausa
(`onPause`, que não faz nada especial) quando o painel abre, nunca para —
o vídeo, os temporizadores e a fila de proof-of-play seguem rodando atrás,
exatamente como a documentação sempre descreveu. O `PlayerView` já usava
`TextureView` (troca feita antes, pra compensar a rotação física da tela)
— pré-requisito de fato pra composição de janelas translúcidas funcionar
direito; `SurfaceView` tem histórico de não compor bem com o que está por
cima ou por baixo dele.

Não há teste automatizado cobrindo isto — é comportamento de janela do
Android, não lógica pura; fica para a verificação em aparelho real (mesma
pendência de estação 5 já registrada em `docs/pendencias.md`).
