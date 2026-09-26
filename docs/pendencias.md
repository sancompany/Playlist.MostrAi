# Pendências — Mostraí Player

## Estado (26/09/2026, MVP 2.0.0)

Código do MVP completo contra `sancompany/MostrAi` → `docs/player-mvp-contract.md`
(matriz: `docs/player-mvp-matriz.md`, 5/5 MATCH). O que falta é físico ou
é decisão do dono. Nenhuma pendência de código.

## Só o dono faz

- **Teste físico na TCL 32S6500S** — `docs/checklist-fisico-producao.md`
  (36 itens, PASS/FAIL), com o APK **debug** da 2.0.0. É o que fecha "a
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
