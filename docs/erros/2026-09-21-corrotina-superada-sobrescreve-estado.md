# Corrotina de uma ação superada sobrescreve o estado de uma ação mais nova

**Marcar como de ecossistema** — o padrão (corrotina assíncrona escrevendo em
estado mutável compartilhado, sem checar se ainda é a "atual" antes de
aplicar o resultado) não é específico deste projeto; qualquer app Android
com Kotlin coroutines e um "item selecionado/ativo" que pode mudar antes de
um `withContext(Dispatchers.IO)` terminar tem o mesmo risco.

## O que aconteceu

`PlayerActivity.mostrarVideo(item)` lançava uma corrotina que fazia trabalho
assíncrono (registrar início na fila de proof-of-play + resolver o cache de
mídia, ambos em `Dispatchers.IO`) e só depois escrevia em estado
compartilhado (`execucaoAtualId`) e no `ExoPlayer` (`setMediaItem`/`prepare`).

Não havia nenhuma checagem de que a corrotina ainda correspondia ao item
"atual" no momento de aplicar o resultado. Cenário concreto: o app troca de
item duas vezes em sucessão rápida (ex.: virada de hora enquanto uma
corrotina anterior ainda está baixando mídia de um item anterior). A
corrotina mais antiga, que começou primeiro mas demorou mais (por causa do
download), terminava DEPOIS da mais nova — e sobrescrevia
`execucaoAtualId` e o `MediaItem` do player com o item errado, fazendo o
vídeo pular de volta para uma peça antiga e atribuindo o próximo
`STATE_ENDED` ao `execucaoId` errado (corrompendo o proof-of-play).

## A regra que isso gerou

Toda corrotina que faz trabalho assíncrono e depois escreve em estado
compartilhado "atual" (item selecionado, tela ativa, requisição em
andamento) precisa guardar um identificador da geração/versão no momento em
que foi lançada, e conferir se ainda é a atual **antes** de aplicar
qualquer resultado — não confiar na ordem de chamada para prever a ordem de
conclusão.

## Onde foi corrigido

`PlayerActivity.kt` — contador `geracaoReproducao`, incrementado em
`tocarItemAtual()`; `mostrarVideo` confere `minhaGeracao == geracaoReproducao`
antes de tocar no estado.
