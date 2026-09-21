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
  JSON local.
- **Por quê**: quem ajusta a margem na prática é o dono, olhando a imagem
  real na TV — hoje isso significa reeditar um arquivo e reinstalar/reiniciar
  o app; pelo admin, é um campo que se ajusta remotamente, sem tocar no
  aparelho de novo.
- **De onde veio**: pedido do dono, 21/09/2026 — decisão explícita de que
  `margemVmin` deve ser configurada pelo próprio site admin.
- **O que toca**: precisa de suporte do backend primeiro — um campo em
  algum contrato (`sancompany/mostrai`, outra sessão) que o app ainda não
  consome. Do lado deste app: `ConfigAparelho.margemVmin` passaria a ter
  uma origem remota (buscada e cacheada, como a playlist), além ou no lugar
  da local; `PlayerActivity.aplicarMargemOverscan` já reage a qualquer
  mudança no valor, não precisa de reescrita.
- **Quando vale a pena**: quando o contrato do backend definir onde esse
  campo mora. Até lá, o campo `margemVmin` continua existindo nos arquivos
  de provisionamento local (`dispositivos/*.json`, `mostrai-config.json`) —
  é o único caminho disponível por enquanto.

## Atualização remota (OTA)

Já está em `README.md`, "Em aberto", item 2 — mantido lá porque é decisão
que precisa ser tomada antes de virar item de próxima versão ou de v1.
