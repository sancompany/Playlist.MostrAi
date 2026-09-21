# Pendências — Mostraí Player

## Só o dono faz

- **Verificar o app rodando em hardware real.** Esta sessão não tem acesso a
  um aparelho Android TV nem a um emulador viável (ambiente sem `/dev/kvm`,
  sem aceleração de virtualização). Instalar o APK (`README.md`, "Instalar e
  provisionar em bancada") num SEMP TCL 32S6500S ou equivalente e confirmar:
  auto-boot, reprodução em laço, painel de manutenção (3 toques de OK + PIN),
  margem de overscan visível corretamente. É o que fecha "a versão inicial
  no ar" da estação 5 para este tipo de projeto (`CONSTRAINTS.md`).
- **Testar contra o backend real** quando o contrato novo (seção 6) estiver
  no ar em `sancompany/mostrai` — hoje só foi testado com JSON sintético nos
  testes unitários (`PlaylistJsonTest`), nunca contra uma resposta real do
  servidor.
- **Decidir as 4 questões em aberto** (ver `README.md`, "Em aberto"): ciclo
  de vida quando o Android mata o app, OTA, PIN universal × PIN por tela,
  provisionamento de campo.
- **Gerar e guardar o keystore de release**, fora deste repositório — hoje
  só existe build debug (assinatura de teste).
- **Pedir ao backend um campo para `margemVmin` por tela, e por lado** (contrato
  de `sancompany/mostrai`, outra sessão) — decisão do dono, 21/09/2026, **reafirmada
  no mesmo dia**: `margemVmin` deve ser configurável pelo próprio site admin, não só
  pelos arquivos de provisionamento local, e como 4 valores independentes (um por
  lado — topo/base/esquerda/direita), não um único número igual nos 4. Ver
  `docs/proximas-versoes.md`, "`margemVmin` configurada pelo admin, não pelo
  arquivo local", para o que isso exige dos dois lados.

## Trabalho desta versão

Todos os blocos do MVP (seção 3 do escopo original) estão implementados —
ver `CLAUDE.md`, "Estado na esteira". Nada pendente de código nesta versão
além do que está listado acima.

## Bloqueios que travam a esteira

Nenhum no momento — CI verde, sem push nem migration pendente do dono para
o trabalho já commitado.
