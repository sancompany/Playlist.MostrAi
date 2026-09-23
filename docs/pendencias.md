# Pendências — Mostraí Player

## Só o dono faz

- **Rodar o checklist físico da auditoria de confiabilidade** —
  `docs/auditoria-confiabilidade-2026-09-23.md`, seção 8 (13 itens). A
  auditoria só verificou o que roda sem a TV; abertura, rotação no primeiro
  boot, relógio após falta de energia, `BOOT_COUNT`, watchdog, OTA e teclas
  do controle dependem do aparelho.
- **Decidir os riscos de produto da auditoria** (mesmo documento, seção 7):
  duração mínima de exibição para contar comprovante (RSK-008), primeira
  exibição esperando download (RSK-001), prazo de segurança para o vídeo de
  abertura (RSK-005).

- **Verificar o app rodando em hardware real.** Esta sessão não tem acesso a
  um aparelho Android TV nem a um emulador viável (ambiente sem `/dev/kvm`,
  sem aceleração de virtualização). Instalar o APK (`README.md`, "Instalar e
  provisionar em bancada") num SEMP TCL 32S6500S ou equivalente e confirmar:
  auto-boot, reprodução em laço, painel de manutenção (3 toques de OK + PIN),
  margem de overscan visível corretamente. É o que fecha "a versão inicial
  no ar" da estação 5 para este tipo de projeto (`CONSTRAINTS.md`).
  **Inclui confirmar, ao abrir o painel: (a) que o app não cai, e (b) que o
  vídeo por trás continua rodando** — os dois são achados de revisão de
  21/09/2026, um encadeado no outro. `PainelActivity` ganhou um tema
  translúcido (`Theme.MostraiPlayer.Translucido`) pra não interromper o
  vídeo (`docs/erros/2026-09-21-painel-parava-o-player-em-vez-de-so-cobrir.md`);
  a revisão seguinte achou que esse tema, combinado com o
  `screenOrientation="landscape"` que a Activity já tinha, derrubava o app
  no Android 8.0 — versão exata do parque instalado — com
  `IllegalStateException`. O `screenOrientation` foi removido
  (`docs/erros/2026-09-21-painel-translucido-com-orientacao-fixa-derrubava-o-app.md`).
  Os dois são comportamento de janela do Android, sem como testar sem
  aparelho real: **abrir o painel na TV é o primeiro teste a fazer.**

  **Ampliado pelo lote V2 (23/09/2026)** — a lista de verificação na TV
  passa a incluir, além do acima:
  - o player como **launcher padrão** (`CATEGORY_HOME`): escolher "sempre" na
    primeira instalação e confirmar que a tecla Home volta ao player;
  - o **watchdog** reabrindo o player depois de sair para outro app;
  - o **diálogo de instalação** do OTA (exige autorizar "instalar apps
    desconhecidos" para o pacote — mais um passo da primeira instalação);
  - o **rate limit do PIN** bloqueando após 3 erros;
  - `Device Owner: sim/não` no painel, que responde a pendência abaixo.
- **Testar contra o backend real** quando o contrato novo (seção 6) estiver
  no ar em `sancompany/mostrai` — hoje só foi testado com JSON sintético nos
  testes unitários (`PlaylistJsonTest`), nunca contra uma resposta real do
  servidor.
- **Decidir as 4 questões em aberto** (ver `README.md`, "Em aberto"): ciclo
  de vida quando o Android mata o app, OTA, PIN universal × PIN por tela,
  provisionamento de campo.
- **Gerar e guardar o keystore de release**, fora deste repositório —
  **bloqueador da frota, não item de backlog.** Toda atualização OTA precisa
  ser assinada com a mesma chave do APK instalado; o Android recusa troca de
  assinatura. Se a primeira leva de TVs sair com assinatura de depuração, ou
  com uma chave que se perca, essas telas nunca poderão ser atualizadas
  remotamente — cada uma vira visita presencial, para sempre. A
  infraestrutura já está pronta (`keystore.properties`, fora do Git); falta
  só gerar e guardar. Passo a passo em `RUNBOOK.md`, "Chave de assinatura".
- **Testar Device Owner no SEMP TCL 32S6500S.** Decide se lock task e
  instalação silenciosa são possíveis neste parque. Há relato documentado de
  `dpm set-device-owner` falhar em TV Android da TCL
  (`docs/proximas-versoes.md`). O player já detecta e funciona sem — este
  teste só define se dá para endurecer mais, não bloqueia o lançamento.
- ~~Pedir ao backend um campo para `margemVmin` por tela, e por lado~~ —
  **RESOLVIDO em 22/09/2026**: backend (`sancompany/mostrai`, migration 069)
  expõe os 4 valores por tela, admin edita, heartbeat entrega; este app lê e
  aplica em runtime (`HeartbeatJson`, `PlayerActivity.heartbeatPeriodico`).
  Detalhe em `CLAUDE.md` (estado na esteira) e `PARA-O-BACKEND.md`. Falta só
  a verificação em hardware real, já coberta pelo item acima ("margem de
  overscan visível corretamente").
- **Vídeo de fundo vem do site, não do app** — decisão do dono, 21/09/2026:
  ao contrário dos assets de marca acima (que são locais, embutidos no
  APK), o vídeo de fundo deve ser servido pelo próprio backend/admin, não
  hardcoded no aplicativo. **Lado do app já pronto (21/09/2026)**: o app
  agora decide tocar vídeo ou desenhar a tela institucional local só pela
  presença de `url` no item — um item institucional com `url` preenchida
  já toca normalmente, hoje, sem precisar de outra versão do app. Falta só
  o backend (`sancompany/mostrai`, outra sessão) preencher essa `url`
  quando o admin configurar um vídeo de fundo — resumido em
  `PARA-O-BACKEND.md`.

## Trabalho desta versão

Todos os blocos do MVP (seção 3 do escopo original) estão implementados —
ver `CLAUDE.md`, "Estado na esteira". Nada pendente de código nesta versão
além do que está listado acima.

## Bloqueios que travam a esteira

Nenhum no momento — CI verde, sem push nem migration pendente do dono para
o trabalho já commitado.
