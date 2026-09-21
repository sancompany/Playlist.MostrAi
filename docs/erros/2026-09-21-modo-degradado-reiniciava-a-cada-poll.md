# Modo degradado reiniciava o vídeo a cada busca periódica

**Marcar como de ecossistema** — o padrão (lógica de reconciliação
compartilhada entre um modo "pleno" e um modo "degradado", escrita
pensando só no modo pleno, com uma suposição que deixa de valer no
degradado e degenera silenciosamente para pior que a linha de base) não é
específico deste projeto: qualquer cliente com fallback de contrato
versionado tem o mesmo risco se o caminho degradado não for testado à
parte do caminho novo.

## O que aconteceu

`PlayerActivity.atualizarPlaylist` decidia se reiniciava a exibição e em
que índice com esta lógica: início frio ou janela nova → reposiciona por
tempo e reinicia; senão, tenta reancorar pelo `itemProgramacaoId` do item
em exibição — se achar, só atualiza o índice sem reiniciar; se não achar,
reposiciona por tempo e reinicia.

Em modo degradado (contrato antigo, `versaoContrato` nulo) nenhum item tem
`itemProgramacaoId` — o campo é sempre `null`. A tentativa de reancorar
por id, então, **sempre falhava**. E como `janelaInicio` também não existe
em modo degradado, `calcularIndiceInicial()` sempre devolvia `0`. Como
`janelaId` também é sempre `null` nesse modo, a comparação "a janela
mudou?" nunca detecta mudança nenhuma — então toda busca periódica (a cada
15 minutos, mais a busca extra na virada da hora) caía no ramo de
reancoragem, falhava, e reiniciava o player do item 0.

Ou seja: em modo degradado, o app cortava a exibição em andamento — sem
`STATE_ENDED`, sem virar comprovante — a cada 15 minutos, o dia inteiro.
Pior que o viés estatístico pro início da lista que a retomada por posição
temporal (decisão 7.1) existe justamente para evitar: aqui não era viés de
distribuição, era interrupção ativa e recorrente. Como o backend
(`sancompany/mostrai`) ainda serve o contrato antigo — o contrato novo
está em implementação em paralelo, outra sessão — este era o caminho que
o app realmente executaria se instalado hoje, não um caso de borda teórico.

## A regra que isso gerou

Lógica de reconciliação que muda de comportamento por modo/versão de
contrato precisa de um ramo explícito pra cada modo, não um fallback
implícito que só funciona por acidente no modo mais comum. E cada ramo
precisa de teste próprio — o suite existente cobria só o modo novo
(`PosicaoNaPlaylistTest` sempre usa `itemProgramacaoId` presente); nada
exercitava reancoragem em modo degradado.

## Onde foi corrigido

A decisão saiu de dentro de `PlayerActivity` para
`ReposicionamentoPlaylist.decidir()` (objeto puro, sem Android, testável
sem Robolectric) — mesmo padrão de extração já usado em
`PosicaoNaPlaylist` e `ChaveCache`, pelo mesmo motivo: lógica de decisão
merece ser testável sem framework. Ganhou um ramo explícito pro modo
degradado: mantém o índice atual (clampado ao tamanho da playlist nova),
sem reiniciar — o item em andamento termina sozinho, e a próxima virada
de `avancar()` já usa a playlist atualizada. Coberto por
`ReposicionamentoPlaylistTest` (8 casos, incluindo os dois cenários do
bug: modo degradado mantendo posição, e clamping quando a playlist nova
encolheu).
