# Pendências — Mostraí Player

## Estado (26/09/2026, MVP 2.0.0)

Código do MVP completo contra `sancompany/MostrAi` → `docs/player-mvp-contract.md`
(matriz: `docs/player-mvp-matriz.md`, 5/5 MATCH). O que falta é físico ou
é decisão do dono. Nenhuma pendência de código.

## Ponto Móvel / offline prolongado (02/10/2026)

- **Backend:** `POST /player/:id/operacao` e a separação conectividade ×
  operação estão em [sancompany/MostrAi#113](https://github.com/sancompany/MostrAi/pull/113)
  (rascunho). Enquanto não for mesclado e publicado, o Player recebe 404
  nessa rota e **guarda** as sessões — nada quebra.
- **Pacote offline de vários dias, contexto base/hospedagem/evento e
  assinatura de manifesto** precisam de desenho no backend —
  `docs/offline-prolongado-proposta-backend.md`. Até lá, a TV fica pronta
  para offline só até o fim da hora corrente; depois, institucional.
- **Teste físico novo:** itens 37–40 do checklist (noite inteira offline,
  reboot sem rede, reconexão).
- **Riscos plausíveis da revisão (ciclo 2), não reproduzidos, sem correção
  ainda:** (a) relógio da TV que salta para a frente (NTP) no meio de uma
  exibição pode fazer a limpeza de órfãos apagar a linha em andamento — a
  idade do órfão é medida pelo relógio de parede; (b) com a fila no teto de
  150.000, o descarte pode escolher como "órfã" a linha que acabou de
  terminar, sem contar a perda; (c) um download grande pode consumir a
  reserva de disco depois da checagem de espaço, que é só antes de baixar.
- **Plausíveis dos ciclos 3 e 4 (o 4 foi limpo), baixos:** (d) `abrir` de
  dois ciclos fora de ordem no lock do SQLite pode fechar a sessão do ciclo
  atual — correção natural: um executor serial único para abrir, checkpoint
  e fechar; (e) item sem `itemProgramacaoId` (fora do contrato) reinicia o
  institucional de reserva a cada minuto; (f) em ~1 de 30 telas a busca da
  virada pode ver a janela anterior ainda válida por milissegundos e não
  agendar a retentativa — fica até o poll de 15 min; (g) redirecionamento
  de CDN com timeout de conexão no `responseCode` vira "falha de rede", não
  "sem rede" (tenta o streaming).

## Só o dono faz

- **Teste físico na TCL 32S6500S** — `docs/checklist-fisico-producao.md`
  (40 itens, PASS/FAIL — 37–40 são do Ponto Móvel), com o APK **debug**. É o que fecha "a
  versão inicial no ar" da estação 5 (`CONSTRAINTS.md`). O primeiro ponto
  crítico é o item 2: a 2.0.0 mudou o manifesto (saíram
  `REQUEST_INSTALL_PACKAGES`, `READ_EXTERNAL_STORAGE`, o painel e um
  receiver), e manifesto só se
  valida no instalador real
  (`docs/erros/2026-09-25-instalador-tcl-recusava-app-com-category-home.md`).
- **Se a imagem aparecer de ponta-cabeça** (item 4): registrar só
  `ROTATION_PHYSICAL_CORRECTION_REQUIRED = 270`. A correção é uma build com
  `Produto.ROTACAO_GRAUS = 270`, nunca rotação configurável.
- **Gerar e guardar o keystore de release**, fora deste repositório —
  **bloqueador da frota, não backlog.** Toda versão futura precisa da mesma
  chave; TV instalada com a chave de debug só troca de versão desinstalando
  e reprovisionando. Passo a passo: `RUNBOOK.md`, "Chave de assinatura".
  Nenhuma sessão automatizada gera essa chave.
- **Definir o PIN de saída no admin** (Rede) antes de gerar o primeiro código
  de instalação — o admin não gera código sem PIN, e o Player não tem PIN
  padrão.
- **Riscos de produto ainda abertos da auditoria de 23/09**
  (`docs/historico/auditoria-confiabilidade-2026-09-23.md`, seção 7): duração
  mínima para contar comprovante (RSK-008), primeira exibição esperando
  download (RSK-001). Não bloqueiam o teste físico.

## Bloqueios que travam a esteira

Só o teste físico acima. CI verde; nenhum push ou migration pendente do dono.
