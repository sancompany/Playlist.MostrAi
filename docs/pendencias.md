# Pendências — Mostraí Player

## Estado (06/10/2026, 3.0.1 — V1 de produção + atualização por pendrive)

Código da V1 completo contra `sancompany/MostrAi` →
`docs/player-mvp-contract.md` + rota de tempo operacional do Ponto Móvel
(`POST /player/:id/operacao`, segmentos, mesclada no backend em
[sancompany/MostrAi#114](https://github.com/sancompany/MostrAi/pull/114)).
Matriz: `docs/player-mvp-matriz.md`. Target/compile 36, minSdk 26
(`docs/adr/0001-target-sdk-moderno.md`). 3.0.0 (`versionCode 4`) é a base;
3.0.1 (`versionCode 6`) é o candidato, com a atualização oficial por
pendrive (`docs/atualizacao-usb.md`) e a mesma chave. Estado: **PRONTO PARA
TESTE FÍSICO** — o atualizador por pendrive **não** está aprovado até os
itens 46–61 do checklist passarem na TCL.

## Backend

- **Heartbeat de 15 s como fonte única — fechado.**
  [sancompany/MostrAi#115](https://github.com/sancompany/MostrAi/pull/115)
  mesclado e publicado (produção em `deafc7d`, 05/10/2026): tolerância "sem
  comunicação" = 8 batidas = 2 min, texto público "a cada 15 segundos".
  `TELA_SEM_SINAL_MIN` **não** está definida em produção (conferido pela API
  do Northflank, só a presença da chave) — vale o padrão de 2 min.

## Decisões

- **DECISÃO — Operação prolongada sem conectividade não faz parte da V1.**
  Pontos usam internet local ou dados móveis (hotspot, roteador 4G/5G). A
  Mostraí é *online-first*: offline é tolerância a interrupções temporárias,
  não modo normal de operação. Pacote offline de vários dias, manifesto
  comercial de vários dias e rota nova de backend para isso: **cancelados**
  (05/10/2026, definitivo para a V1). Proposta antiga em
  `docs/historico/offline-prolongado-proposta-backend.md`. Continua valendo a
  resiliência: cache da programação autorizada, reprodução até o fim da
  janela, institucional depois, comprovantes e segmentos guardados até a
  confirmação, reboot sem rede, recuperação ao reconectar.
- **Produção começa do zero no 3.0.0**: toda TV recebe o Player depois de
  remover o antigo e apagar os dados; nada de instalações de teste é
  migrado. Daí em diante, toda atualização usa a mesma chave.

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

**Atualização por pendrive (3.0.1), riscos aceitos:** TV Android 11+ sem o
seletor de pastas do Android (firmware sem DocumentsUI) não lê o pendrive —
o bloco técnico diz isso e a atualização volta a ser pelo gerenciador de
arquivos; decidir "Acesso a todos os arquivos" só se aparecer TV assim no
parque (`docs/permissoes-especiais.md`). Se a TCL expõe o pendrive aos apps
(`/storage/<UUID>` legível com a permissão de armazenamento) é o item
físico mais incerto (`docs/hardware/tcl-32s6500s.md`). Uma pausa do
watchdog deixada por um boot anterior pode valer até 10 min depois do
reboot (o `BootReceiver` abre o Player e desfaz a pausa do mesmo jeito).
"Ligar a tela" é só teste manual; efeito de `TURN_SCREEN_ON` no Android
14+ não confirmado. Pacote de teste (`-teste-`, NAO-DISTRIBUIR) assinado
com a chave oficial é aceito pelo Player se chegar a uma TV de cliente — a
TV iria para um `versionCode` queimado; a guarda é só a marcação do pacote.

**Revisão do delta da 3.0.1 (06/10/2026, duas frentes):** 0 HIGH; 5 MEDIUM
corrigidos com teste (ver `CLAUDE.md`); baixos aceitos: mensagem "cópia
incompleta" para arquivo de tamanho desconhecido acima do teto e "pendrive
ilegível" para disco cheio durante a escrita (só texto).

## Só o dono faz

- **Custódia da chave definitiva** — gerada em 05/10/2026 por autorização
  explícita do dono, numa sessão efêmera, e entregue a ele para download
  (`mostrai-release.jks` + arquivo de custódia com as senhas). Impressão
  digital registrada em `scripts/certificado-producao.sha256`
  (`8c4ea2cc33201dd3…`). Cabe ao dono: 3 cópias do `.jks` (computador,
  externa/offline, segunda cópia segura), senhas guardadas separadas, e
  conferir uma cópia restaurada com `keytool -list -v` contra a impressão
  registrada. A cópia da sessão some com o contêiner.
- **Guardar o `REGISTRO.txt`** do primeiro APK oficial (entregue junto com o
  APK) fora do Git.
- **Teste físico nas duas TCLs** — `docs/checklist-fisico-producao.md`
  (65 itens: TV A operação normal no 3.0.0, TV B quedas temporárias já no
  3.0.1, 3.0.0 → 3.0.1 à mão com a mesma chave, atualização por pendrive
  com o pacote de teste `versionCode 7`, vídeo em pé 1080×1920, soak em
  etapas 2–4 h → 24 h → 48–72 h, teste de ligar a tela). É o que fecha "a
  versão inicial no ar" da estação 5. Depois do teste do pendrive a TV de
  teste fica no `versionCode 7`: desinstalar e reinstalar o 3.0.1 oficial
  antes de uso real; a próxima versão oficial é `versionCode 8`+.
- **Guardar** o `REGISTRO.txt` e o `Mostrai-USB-3.0.1.zip` (com o SHA-256)
  do 3.0.1 junto com a chave.
- **Se a imagem aparecer de ponta-cabeça**: registrar só
  `ROTATION_PHYSICAL_CORRECTION_REQUIRED = 270`.
- **Definir o PIN de saída no admin** antes de gerar o primeiro código de
  instalação.
- **Branch padrão do repositório**: hoje é `claude/festive-goldberg-4gdhqi`,
  não `main`. Só o dono troca (GitHub › Settings › Branches).
- **Riscos de produto ainda abertos da auditoria de 23/09**
  (`docs/historico/auditoria-confiabilidade-2026-09-23.md`, seção 7):
  duração mínima para contar comprovante (RSK-008), primeira exibição
  esperando download (RSK-001). Não bloqueiam o teste físico.

## Bloqueios que travam a esteira

A chave definitiva e o teste físico acima.
