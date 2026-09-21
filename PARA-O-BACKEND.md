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

### 1. `margemVmin` configurável pelo admin, por lado (não 1 valor por tela)

Hoje `margemVmin` (compensação de overscan) só existe nos três caminhos de
provisionamento **locais** deste app (build embutido, `mostrai-config.json`
no pendrive, extras de `adb`) — um `Float` único, mesma margem nos 4 lados.

Decisão do dono, reafirmada: isso devia vir do próprio site admin, e como
**4 valores independentes** (topo/base/esquerda/direita), porque a folga de
overscan varia por lado, não só por tela. Sem um campo do backend pra isso,
o app não tem como buscar. Detalhe do que muda dos dois lados em
`docs/proximas-versoes.md`, seção "`margemVmin` configurada pelo admin, não
pelo arquivo local".

### 2. Vídeo de fundo institucional servido pelo backend, não embutido no app

Decisão do dono: o vídeo de fundo que toca quando não há programação pra
aquela hora deve vir do próprio backend/admin, não ser um asset fixo dentro
do APK. Hoje o item institucional que o backend manda (`institucional:
true`) **nunca tem `url`** — é assim que o app sabe que é pra desenhar a
peça institucional local em vez de tocar mídia. Pra isso mudar, o backend
precisaria expor essa mídia de algum jeito — o caminho mais natural é o
próprio item institucional passar a vir com uma `url` preenchida quando o
admin configurar um vídeo de fundo, e sem `url` continuar caindo no
desenho local (degradê + legenda) como fallback. Ainda **não há contrato**
para isso — registrado como direção em `docs/pendencias.md`, não como
trabalho pronto pra puxar.

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
