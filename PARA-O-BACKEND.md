# Para quem lê isto do lado do backend (`sancompany/mostrai`)

Resumo de handoff — o que o Mostraí Player (este repositório) já assume do
backend, e o que precisa do backend pra fechar. Escrito pra ser lido sem o
histórico da conversa que gerou este repo: se algo aqui parecer
desatualizado, os documentos citados são a fonte de verdade, não este
arquivo — ele existe só pra apontar pra eles.

## O que é este projeto

App Android TV nativo (Kotlin, sideload por pendrive) que toca a
programação publicitária de uma tela do Mostraí e manda comprovante de
exibição de volta. Não é área administrativa, não tem conta de usuário —
só um cliente do backend. Classificação, escopo e o porquê de cada decisão
estão em `CLAUDE.md` e `docs/specs/2026-09-21-mostrai-player.md`.

Estado atual: estação 5 (Construção) quase fechada, só falta confirmar em
hardware real (`docs/pendencias.md`). PR aberto com o lote mais recente:
[PR #2](https://github.com/sancompany/Playlist.MostrAi/pull/2).

## O contrato que este app já assume

Documentado por inteiro em `docs/funcional.md`, seção "Contrato com o
backend" — aqui só o resumo de quem lê rápido:

- O app opera nas **duas formas** desde o início: array puro (contrato
  antigo, "modo degradado") e envelope com `versaoContrato`/`janelaId`/
  `itemProgramacaoId`/`criativoId` (contrato novo) — troca automática por
  formato da resposta, sem flag. Ver `network.PlaylistJson`.
- `POST /player/:dispositivoId/played` em lote, com `execucaoId` (ver
  `network.PlayedJson`, `proof.FilaProofOfPlay`).
- Duas garantias que o app **depende** do backend manter, sem aviso prévio
  se quebrarem:
  1. `itemProgramacaoId` deriva da posição na sequência congelada da hora,
     nunca do índice bruto do array da resposta (a reancoragem do app
     depende disso pra não perder posição quando a playlist muda no meio
     da janela).
  2. `criativoId → url` é imutável — criativo trocado é `criativoId` novo.
     O app usa isso como chave de cache local sem revalidar contra o
     servidor; se a mesma `criativoId` puder um dia apontar pra uma `url`
     diferente, o cache local ficaria servindo mídia errada.

## O que este app precisa do backend (pendente, do lado de lá)

O lado deste app está pronto pros dois itens abaixo — o app já sabe
consumir o que falta assim que existir. Não é "vamos ter que atualizar o
player depois" — é só o backend expor o campo.

### 1. `margemVmin` configurável pelo admin, por lado (não 1 valor por tela)

**Lado do app pronto (21/09/2026).** `margemVmin` deixou de ser um valor
único e agora são 4 campos independentes —
`ConfigAparelho.margemVminTopo/Base/Esquerda/Direita`, sempre em termos
**visuais** (o que o operador vê olhando pra tela já montada — o app já
resolve a conversão pra rotação física da tela sozinho, o backend não
precisa saber disso). Hoje esses 4 valores só vêm dos três caminhos de
provisionamento **locais** (build embutido, `mostrai-config.json`, extras
de `adb`) — os mesmos 4 nomes de campo (`margemVminTopo`, `margemVminBase`,
`margemVminEsquerda`, `margemVminDireita`), como float.

O que falta é só do lado do backend: um campo (ou 4) no cadastro da tela
que o admin edite, e uma forma do app buscar esse valor (mais natural:
junto da resposta de `/playlist`, ou do cadastro do dispositivo). Quando
esse campo existir, é só decidir a precedência com os caminhos locais
(sugestão: backend sobrescreve local, mesmo padrão que os outros campos já
seguem) — não precisa de mudança nenhuma na forma como o app já entende
"4 valores por lado, em vmin, em termos visuais". Detalhe em
`docs/proximas-versoes.md`, seção "`margemVmin` configurada pelo admin,
não pelo arquivo local".

### 2. Vídeo de fundo institucional servido pelo backend, não embutido no app

**Lado do app pronto (21/09/2026).** O app agora decide se toca vídeo ou
desenha a tela institucional local **só pela presença de `url`** no item
da playlist — não mais pela flag `institucional`. Ou seja: um item com
`institucional: true` **e** uma `url` preenchida já toca essa `url`
normalmente hoje, sem precisar de outra versão do app. Sem `url` (o caso
de hoje), continua caindo no desenho local (degradê + legenda) como
fallback — nada mudou nesse caso.

O que falta é só do lado do backend: quando o admin configurar um vídeo de
fundo pra uma tela, o item institucional que `/playlist` manda pra ela
precisa vir com essa `url` preenchida (e `contabiliza: false`, já que
vídeo de fundo institucional não é anúncio pago — mesma regra que já vale
pra qualquer item institucional, RN-03 em `docs/funcional.md`). Não há
contrato pra esse campo ainda — o app está pronto, falta o backend decidir
e expor.

## O que NÃO é pedido ao backend (pra não confundir)

- **PIN do painel de manutenção** — sempre 4 dígitos, local ao aparelho
  (`ConfigAparelho.pinPainel`), sem relação com o backend.
- **Rotação de tela (`rotacaoTela`)** — compensa a TV montada fisicamente
  de lado; também local, um dos três caminhos de provisionamento, sem
  pedido de campo novo no backend.
- **Ícone, vídeo de abertura, telas institucionais de erro/carregando/não
  provisionado** — assets de marca embutidos no APK, não dependem de nada
  do backend.
- **Atualização remota (OTA)** — o dono perguntou se dá pra lançar
  atualização pelo próprio painel admin; a resposta ficou registrada como
  pesquisa em `docs/proximas-versoes.md`, "Atualização remota (OTA)", com
  duas opções (instalação com confirmação vs. silenciosa via Device
  Owner). **Nada decidido, nada pedido ainda** — se algum dia isso avançar,
  o pedido concreto ao backend seria hospedar o `.apk` e um manifesto de
  versão, mas essa etapa não começou.

## Onde olhar para mais detalhe

- `docs/funcional.md` — contrato completo, regras de negócio (RN-01 a
  RN-14), o que cada tela faz.
- `docs/pendencias.md` — tudo que falta, e o que é "só o dono decide" vs.
  "precisa do backend".
- `docs/proximas-versoes.md` — ideias registradas pra depois, com "de onde
  veio" e "o que toca" de cada uma.
- `docs/inventario-de-dados.md` — o que este app guarda e transmite, pra
  quem cuida de dado/privacidade do lado do backend também.
