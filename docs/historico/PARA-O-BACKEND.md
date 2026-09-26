# Para quem lê isto do lado do backend (`sancompany/mostrai`)

> **Atualizado em 23/09/2026 — comece por outro lugar.** O player passou a
> implementar o contrato V2 inteiro. O documento exato do que existe está em
> **`docs/player-v2-contract.md`**, e a lista de trabalho do lado do backend
> em **`docs/player-v2-mostrai-checklist.md`**. Este arquivo continua válido
> como resumo de entrada, mas os dois acima são a fonte de verdade.
>
> Nada do V2 é bloqueante: o player novo roda contra o backend de hoje sem
> nenhuma mudança, porque toda rota V2 degrada sozinha quando responde 404.

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

## O que este app precisa do backend

### 1. `margemVmin` configurável pelo admin, por lado — RESOLVIDO em 22/09/2026

O backend (`sancompany/mostrai`, migration 069) ganhou 4 colunas em
`dispositivos` (`margem_superior/direita/inferior/esquerda`, editáveis na
aba Telas do admin) e passou a entregar isso a cada heartbeat:
`POST /player/:dispositivoId/heartbeat` devolve
`{ok, margens: {superior, direita, inferior, esquerda}}`, em vmin, sempre
em termos visuais — mesmo formato que o player web já consome.

Do lado deste app: `network.HeartbeatJson.parseMargens` lê essa resposta
(`superior`/`inferior` viram `topo`/`base` só pra bater com o nome que
`MargensOverscan` já tinha); `MostraiApi.heartbeat()` devolve
`MargensOverscan?` em vez de `Boolean` (`null` = heartbeat falhou ou o
servidor não mandou margens — nunca zera o que já estava configurado).
`PlayerActivity.heartbeatPeriodico` grava em `ConfigAparelho` e reaplica
`RotacaoTela.aplicar` a cada resposta com valor — o backend sobrescreve o
que veio de provisionamento local assim que a tela ficar online, exatamente
a precedência sugerida abaixo (mantida por registro histórico). 70 → 74
testes (`HeartbeatJsonTest`, novo).

<details>
<summary>Pedido original (21/09/2026), mantido como registro</summary>

`margemVmin` deixou de ser um valor único e virou 4 campos independentes —
`ConfigAparelho.margemVminTopo/Base/Esquerda/Direita`, sempre em termos
**visuais** (o que o operador vê olhando pra tela já montada — o app já
resolve a conversão pra rotação física da tela sozinho, o backend não
precisava saber disso). Sugestão que acabou virando a implementação real:
"backend sobrescreve local, mesmo padrão que os outros campos já seguem".
Detalhe em `docs/proximas-versoes.md`, seção "`margemVmin` configurada pelo
admin, não pelo arquivo local".
</details>

### 2. Vídeo de fundo institucional servido pelo backend, não embutido no app — pendente

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
