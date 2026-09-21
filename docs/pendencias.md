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

## Trabalho desta versão que falta

- Cache local de mídia (bloco 4 do MVP) — em andamento nesta mesma sessão,
  estação 5.
- Mais testes de integração (parsing das duas formas do contrato,
  ciclo de vida da fila de proof-of-play com Robolectric) — ver
  `docs/proximas-versoes.md` se não couber nesta estação.

## Bloqueios que travam a esteira

Nenhum no momento — CI publicado e sendo verificado; sem push nem migration
pendente do dono para o trabalho já commitado.
