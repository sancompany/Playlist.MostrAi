# Pendências — Mostraí Player

## Estado (05/10/2026, 3.0.0 — V1 de produção)

Código da V1 completo contra `sancompany/MostrAi` →
`docs/player-mvp-contract.md` + rota de tempo operacional do Ponto Móvel
(`POST /player/:id/operacao`, segmentos, mesclada no backend em
[sancompany/MostrAi#114](https://github.com/sancompany/MostrAi/pull/114)).
Matriz: `docs/player-mvp-matriz.md`. Target/compile 36, minSdk 26
(`docs/adr/0001-target-sdk-moderno.md`). Estado: **PLAYER PRONTO PARA TESTE
FÍSICO DE RELEASE** — o que falta é físico ou é do dono.

## Backend

- **Heartbeat de 15 s como fonte única** — o backend ainda dizia 5 min (e
  tolerava 6,5 min de silêncio como operação). Alinhado em
  [sancompany/MostrAi#115](https://github.com/sancompany/MostrAi/pull/115)
  (rascunho): tolerância "sem sinal" = 8 batidas = 2 min, e o texto público
  "a cada 15 segundos". Até ser mesclado e publicado, o admin mostra "sem
  sinal" com atraso e o tempo operacional pelo heartbeat tolera buracos de
  até 6,5 min — o Player não muda nada.
- **Pacote offline de vários dias** (contexto base/hospedagem/evento,
  manifesto assinado): precisa de rota nova no backend —
  `docs/offline-prolongado-proposta-backend.md`. Decisão do dono
  (05/10/2026): o cliente só é implementado quando o backend tiver o
  endpoint. Até lá, a TV fica pronta para offline até o fim da janela da
  hora corrente; depois, institucional.

## Riscos conhecidos, aceitos

- **(e)** item sem `itemProgramacaoId` (fora do contrato) reinicia o
  institucional de reserva a cada minuto.
- **(f)** em ~1 de 30 telas a busca da virada pode ver a janela anterior
  ainda válida por milissegundos e não agendar a retentativa — fica até o
  poll de 15 min.
- **(g)** redirecionamento de CDN com timeout de conexão no `responseCode`
  vira "falha de rede", não "sem rede" (tenta o streaming).
- Tempo operacional de um boot que nunca falou com o servidor é descartado
  (contrato §8.5: sem âncora não há relógio do servidor). Contado no diário
  (`OPERACAO_SEM_ANCORA`).
- Retorno automático em TV Android 10+ depende de "Exibir sobre outros
  apps", concedida por pessoa (`docs/android-modernizacao.md`).
- Media3 1.4.1 (sem as correções das 1.5–1.11); subir pede teste na TCL.
- Baixos da revisão focal de 05/10, aceitos: segmento aberto estendido
  depois de um intervalo sem checkpoint (aparelho suspenso com a Activity
  na frente) conta o intervalo; download sem `Content-Length` que bate na
  reserva é refeito a cada exibição em vez de liberar espaço antes; um id de
  exibição pode ficar protegido da limpeza até o processo reiniciar se a
  saída por PIN cancelar a corrotina de fim; TV Android 10+ "low RAM" pode
  não deixar conceder "Exibir sobre outros apps" (checklist, seção Android
  10+); a permissão nova pode ser o que um instalador recusa (checklist
  item 1).

**Revisão focal de 05/10/2026 (três frentes em paralelo):** Android/ciclo de
vida — nenhum HIGH/MEDIUM aberto; offline/persistência — 1 HIGH (TV recém-
instalada não contava tempo operacional: a arrumação do começo do ciclo
fechava o segmento que a instalação acabara de abrir) e 3 MEDIUM (cartão
preso em estado de erro; âncora tirada de playlist guardada; cartão "sem
conteúdo" contando como operação), todos corrigidos com teste;
segurança/release — 4 HIGH e 3 MEDIUM nos scripts novos (conferência de
permissões sempre vermelha; `debuggable`/texto puro e varredura de segredos
passando sem conferir nada; CI apagando o APK debug antes de conferi-lo;
achado do histórico perdido por SIGPIPE; nenhuma trava de "mesma chave";
árvore suja não detectada), todos corrigidos e conferidos contra APK real.

Corrigidos nesta versão (eram plausíveis da revisão de 02/10): (a) limpeza
de órfãos apagando a exibição em andamento quando o relógio salta; (b) fila
cheia descartando a exibição em andamento; (c) download consumindo a
reserva de disco depois da checagem; (d) abrir/fechar do tempo operacional
fora de ordem (agora um executor serial único).

## Só o dono faz

- **Gerar e guardar a chave de assinatura definitiva**, fora deste
  repositório — **bloqueador do release**. Passo a passo: `RUNBOOK.md`,
  "Chave de assinatura". Nenhuma sessão automatizada gera essa chave.
- **Gerar o candidato assinado** (`scripts/release-candidato.sh`) e guardar
  o `REGISTRO.txt` (`docs/release-producao.md`).
- **Teste físico nas duas TCLs** — `docs/checklist-fisico-producao.md`
  (45 itens: TV A operação normal, TV B tortura offline, N → N+1 com a
  mesma chave, vídeo em pé 1080×1920, soak de 72 h). É o que fecha "a
  versão inicial no ar" da estação 5.
- **Se a imagem aparecer de ponta-cabeça**: registrar só
  `ROTATION_PHYSICAL_CORRECTION_REQUIRED = 270`.
- **Definir o PIN de saída no admin** antes de gerar o primeiro código de
  instalação.
- **Mesclar o PR do backend do heartbeat** (#115) quando aprovado.
- **Branch padrão do repositório**: hoje é `claude/festive-goldberg-4gdhqi`,
  não `main`. Só o dono troca (GitHub › Settings › Branches).
- **Riscos de produto ainda abertos da auditoria de 23/09**
  (`docs/historico/auditoria-confiabilidade-2026-09-23.md`, seção 7):
  duração mínima para contar comprovante (RSK-008), primeira exibição
  esperando download (RSK-001). Não bloqueiam o teste físico.

## Bloqueios que travam a esteira

A chave definitiva e o teste físico acima.
