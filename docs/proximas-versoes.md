# Próximas versões — Mostraí Player

Ideias para depois. Entrada aqui não autoriza construir nada.

## Relatório na TV

- **O quê**: tela de diagnóstico mais rica no painel (gráfico simples de
  exibições por hora, não só contadores).
- **Por quê**: hoje o painel só mostra números crus; um operador de campo
  sem acesso ao admin não consegue diagnosticar padrão nenhum, só estado
  atual.
- **De onde veio**: prompt original, seção 3 — item explicitamente fora do
  MVP.
- **O que toca**: `PainelActivity`, talvez uma consulta agregada na
  `ProofOfPlayDb`.
- **Quando vale a pena**: se operadores de campo começarem a pedir
  diagnóstico visual em vez de abrir o admin web.

## Telemetria rica (captura de erro, analytics de uso)

- **O quê**: serviço de terceiro (tipo Sentry) capturando exceção com
  contexto, fora do log local.
- **Por quê**: hoje o único jeito de ver um crash é `adb logcat` em bancada
  — sem visibilidade remota de falha em campo.
- **De onde veio**: 21/09/2026, ao escrever `CONSTRAINTS.md` (desproporcional
  para a v1, sem dado pessoal envolvido).
- **O que toca**: nova dependência, novo segredo (chave da API do serviço)
  — mudança estrutural, entra pela Lei 3.
- **Quando vale a pena**: quando o número de telas em campo crescer o
  suficiente para `adb` deixar de ser prático como única fonte de
  diagnóstico.

## Cache de mídia com eviction por uso real (LRU)

- **O quê**: descarte por "menos usado recentemente" em vez de só por
  tamanho/idade.
- **Por quê**: a v1 usa um teto simples de tamanho com descarte do mais
  antigo — funciona, mas pode descartar um criativo que volta a tocar logo
  em seguida.
- **De onde veio**: 21/09/2026, ao implementar o bloco de cache.
- **O que toca**: `CacheMidia` (a criar).
- **Quando vale a pena**: se a métrica de "cache miss" (a criar) mostrar
  descarte de criativo que volta a ser pedido em menos de 24h.

## `margemVmin` configurada pelo admin, não pelo arquivo local

- **O quê**: a margem de overscan deixa de vir só do provisionamento local
  (build embutido, `mostrai-config.json` ou `adb`) e passa a poder ser
  ajustada pelo dono direto no painel admin do backend, por tela — o app
  buscaria esse valor do servidor (provavelmente junto do cadastro da tela
  ou da resposta de `/playlist`) em vez de depender de alguém editar um
  JSON local. **Reafirmado no mesmo dia**: não é um valor só — cada tela
  precisa de 4 valores independentes, um por lado (topo/base/esquerda/
  direita), porque cada TV tem sua própria folga de cada lado.
- **Por quê**: quem ajusta a margem na prática é o dono, olhando a imagem
  real na TV — hoje isso significa reeditar um arquivo e reinstalar/reiniciar
  o app; pelo admin, é um campo que se ajusta remotamente, sem tocar no
  aparelho de novo. E um valor único nos 4 lados já se mostrou errado na
  prática: a folga varia por lado, não só por tela.
- **De onde veio**: pedido do dono, 21/09/2026 — decisão explícita de que
  `margemVmin` deve ser configurada pelo próprio site admin, reafirmada no
  mesmo dia especificando que são 4 valores por lado, não 1 por tela. Mesma
  ideia registrada do lado do backend, com o histórico completo, em
  `sancompany/mostrai`, `docs/proximas-versoes.md`, seção "Margem e
  orientação por tela configuráveis no admin, não só na URL".
- **O que toca**: **lado deste app já pronto (21/09/2026)** —
  `ConfigAparelho.margemVmin` virou 4 propriedades independentes
  (`margemVminTopo/Base/Esquerda/Direita`, expostas juntas como
  `margensOverscan`), já lidas dos três caminhos de provisionamento locais
  e já aplicadas corretamente por `RotacaoTela.aplicar` (que passou a
  colocar o padding em `rotor`, não em `raiz` — só assim uma margem
  assimétrica sobrevive a uma tela com `rotacaoTela` de 90°/270°, ver
  `RotacaoTela.kt`). Falta só o contrato do backend: 4 campos por tela
  (`sancompany/mostrai`, outra sessão), e o app buscar/cachear esses
  valores (provavelmente junto do cadastro da tela ou da resposta de
  `/playlist`, como a playlist já faz) em vez de/além dos locais.
- **Quando vale a pena**: quando o contrato do backend definir onde esses 4
  campos moram — a única peça que falta agora. Até lá, os 4 campos
  continuam vindo só dos arquivos de provisionamento local
  (`dispositivos/*.json`, `mostrai-config.json`) — é o único caminho
  disponível por enquanto.

## Atualização remota (OTA)

Já está em `README.md`, "Em aberto", item 2 — mantido lá porque é decisão
que precisa ser tomada antes de virar item de próxima versão ou de v1.

**Pergunta do dono, 21/09/2026**: dá para lançar atualização pelo próprio
painel admin do backend, em vez de sempre trocar o pendrive? Resposta
técnica, sem código ainda — duas fases independentes:

- **Fase 1 — sem enrollment, funciona em qualquer aparelho.** O admin
  publica um `.apk` novo e um manifesto de versão (versionCode, URL de
  download, talvez checksum). O player (que já faz poll a cada 15 min e
  heartbeat a cada 5 min) compara sua própria versão
  (`BuildConfig.VERSION_CODE`) com a do manifesto, baixa o APK em segundo
  plano se houver novidade, e dispara a instalação via
  `PackageInstaller`/`REQUEST_INSTALL_PACKAGES`. **Limite físico do
  Android**: essa instalação sempre mostra um diálogo de confirmação do
  sistema — alguém precisa estar na loja e tocar "Instalar" no controle
  remoto. Não elimina a visita presencial, mas elimina o pendrive/laptop:
  troca "levar um pendrive configurado" por "apertar OK na TV quando
  aparecer o aviso".
- **Fase 2 — instalação silenciosa, precisa de Device Owner.** Se o
  aparelho for inscrito como Device Owner (Android Enterprise, feito uma
  vez no provisionamento — o aparelho precisa estar "de fábrica", sem
  conta nenhuma, ver `adb shell dpm set-device-owner`), o app ganha
  permissão de instalar pacotes sem diálogo nenhum — atualização
  verdadeiramente sem ninguém na loja. **Não dá para confirmar sem
  hardware real**: não se sabe se o SEMP TCL 32S6500S (Android TV 8,
  fabricante fechado) aceita Device Owner sem alguma trava do fabricante —
  só um teste físico decide, e normalmente exige refazer o provisionamento
  do zero (reset de fábrica) para inscrever.

  **Pesquisa (21/09/2026, sem hardware — sinal, não confirmação):** um
  relato real de usuário (XDA Forums) tentando `dpm set-device-owner` numa
  TCL Android TV (modelo TCL32A5, Android 9 — não o mesmo modelo nem a
  mesma versão do Mostraí, mas mesmo fabricante e mesma categoria de
  produto) bateu em `"Can't set package as device owner"` — o mesmo
  comando funcionou sem problema em Sony TV e em aparelhos móveis. Isso
  não prova que o SEMP TCL 32S6500S vai falhar da mesma forma (modelo e
  versão de Android diferentes, e é uma amostra de um usuário só), mas é
  um sinal real contra a Fase 2, não hipotético — TCL como fabricante tem
  pelo menos um caso documentado de travar esse caminho num aparelho de
  TV. Eleva a prioridade do teste físico antes de investir qualquer linha
  de código na Fase 2. Fontes: [thread original](https://xdaforums.com/t/how-to-set-device-owner-in-tcl-android-tv.4590837/)
  (bloqueado pra fetch automatizado, resumo via busca).

**Recomendação**: começar pela Fase 1 se/quando isso for priorizado —
funciona em qualquer aparelho, sem risco, e já corta a dependência do
pendrive para o caso comum (trocar app, não trocar tela). Fase 2 agora tem
um motivo concreto a mais pra não ser a aposta principal: além de precisar
de teste físico de qualquer forma, já existe um relato real de falha em
TV TCL (fabricante diferente do celular/tablet onde esse caminho é mais
testado). Não vale desenhar o resto em cima de uma suposição não testada
— e essa suposição já tem um dado contra ela.
