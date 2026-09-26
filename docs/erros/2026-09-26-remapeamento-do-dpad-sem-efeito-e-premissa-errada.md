# Remapeamento do D-pad: sem efeito na TV, e com a premissa errada

## O que aconteceu

A 2.0.0 trouxe `ui/DpadRotacionado`, que girava as setas do controle em
`PlayerActivity.dispatchKeyEvent` para "compensar" a rotação de 90° do
conteúdo. A premissa: a busca de foco anda nas coordenadas do layout sem
rotação, então "direita" no layout apareceria "para baixo".

As duas partes estavam erradas.

1. **Premissa.** A TV está montada de lado e o conteúdo gira junto
   exatamente para ficar de pé para quem olha. As coordenadas do layout
   *são* as do instalador: "direita" no layout é "direita" para ele. Só
   haveria o que remapear se a TV não estivesse girada.
2. **Efeito.** No Android 8.0 (bytecode de `ViewRootImpl$ViewPostImeInputStage`
   conferido), `processKeyEvent` entrega o evento à hierarquia e, se ninguém
   consome, chama `performFocusNavigation` com o **mesmo objeto original**.
   O evento remapeado só chegava às Views, que não consomem setas. O foco
   andava pela tecla original — que, pelo item 1, é a certa.

Resultado: código inerte, que só não quebrava por acaso. Qualquer View que
passasse a consumir setas (uma lista, um `ScrollView`) teria ativado o
remapeamento e girado a navegação em 90° na tela de instalação.

## Correção

`DpadRotacionado` e o teste dele removidos; `dispatchKeyEvent` passa o
evento adiante sem mexer. `ProvisionamentoCicloTest` ganhou um teste que
reproduz a navegação da `ViewRootImpl` (`focusSearch` a partir da tecla "1",
com o `rotor` a 90°): DIREITA vai para "2", BAIXO para a linha de baixo,
CIMA para CONECTAR.

## A regra

Antes de "compensar" algo do framework, confirmar por onde o framework
passa de fato — `dispatchKeyEvent` da Activity não é o caminho da navegação
por foco. E a pergunta de geometria é sempre "para quem olha", não "para o
painel".
