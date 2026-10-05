# CONSTRAINTS — o que este projeto não faz

Escopo negativo e limites assumidos. Ver `docs/specs/2026-09-21-mostrai-player.md`
para o porquê de cada decisão de escopo.

## Vetos formais (não se negociam, ver `docs/specs/`)

- **Nunca usuário e senha na TV.** A tela se autentica só por chave de
  aparelho revogável, obtida trocando o ID da tela + código de instalação
  do admin. Qualquer proposta de tela de login é rejeitada de saída.
- **Nenhum segredo versionado.** Chave do aparelho, keystore de assinatura,
  senhas e credenciais ficam fora do Git (`.gitignore`). O keystore de
  release fica com o dono, fora do repositório. A URL de produção
  (`https://mostrai.sancocore.com.br`) **não** é segredo: é o endereço
  público do serviço, fixo no APK por decisão de produto (`Produto.kt`).
- **A tela não decide nada sozinha.** Hora, ordem e o que conta são do
  servidor. O app executa e relata — nunca infere crédito, nunca decide
  elegibilidade.
- **Só conclusão real conta.** A exibição vira comprovante apenas em
  `STATE_ENDED`. Sem limiar de percentual, sem "tocou 90%, vale".
- **O relógio da TV nunca decide negócio.** Toda decisão de crédito, janela e
  item vem do backend (`janelaId`, `itemProgramacaoId`, `servidorAgora`).
  Onde o relógio de parede local é usado (agendar quando acordar na virada da
  hora), é só para *quando* agir, nunca para *o quê* creditar — documentado
  no ponto de uso (`PlayerActivity.agendarViradaDeHora`).

## Fora do escopo (2.0.0; vale para a 3.0.0)

Se não é necessário para instalar, reproduzir, ficar offline, comprovar,
receber config, ajustar margens, respeitar horário, sair com PIN ou se
recuperar, não entra. Em especial, **não** reintroduzir:

- **OTA** (atualização remota). Atualizar é sideload de um APK assinado com a
  mesma chave (`RUNBOOK.md`).
- **Device Owner, MDM, lock task, launcher `HOME`.** O `HOME` foi provado
  incompatível com o instalador da TCL (`docs/erros/2026-09-25-…`). Quem
  traz o player de volta é o `Watchdog`.
- **Serviço em primeiro plano, alarme exato com permissão
  (`SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`), câmera, microfone, BYOD,
  Tizen/webOS.** Nenhum é necessário (`docs/android-modernizacao.md`).
- **Permissão nova sem decisão registrada.** São 4: `INTERNET`,
  `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` e — exceção registrada em
  05/10/2026 (ADR 0001) — `SYSTEM_ALERT_WINDOW`, só como exceção oficial ao
  bloqueio de abrir Activity do segundo plano no Android 10+; o Player
  nunca desenha sobre outros apps. `GuardaMvpTest` e
  `scripts/verificar-apk.sh` falham com qualquer outra.
- **Regra de negócio no Player.** Plano, saldo, benefício, percentual, o
  que conta como hospedagem: o Player registra fatos (comprovante,
  segmento, estado); o backend calcula.
- **Painel técnico na TV** (gesto de 3 toques, tela de diagnóstico). O único
  diálogo local é o PIN de saída. **Exceção registrada (02/10/2026, pedido
  do dono — Ponto Móvel; ampliada em 05/10/2026):** a tela de PIN mostra,
  abaixo do teclado, um bloco técnico de suporte só leitura (`InfoSuporte`:
  versão, aparelho, Android, estado, conexão, programação válida até,
  mídias em cache, espaço, fila, tempo operacional pendente, último erro,
  retorno automático bloqueado, "pronto para offline até"). Só aparece para
  quem apertou VOLTAR diante da TV; não tem ação, não abre por gesto, nunca
  vai ao público e nunca mostra chave, token, código, cabeçalho, URL ou
  dado pessoal (`SegredoForaTest`).
- **Provisionamento por JSON, pendrive, `BuildConfig` por tela ou extras de
  ADB.** A única forma é ID da tela + código de instalação digitados na TV.
- **`baseUrl` variável, multi-host, rotação configurável ou
  multi-orientação.** URL e rotação são constantes de `Produto.kt`. Se a TV
  mostrar de ponta-cabeça, a correção é uma build nova com 270°.
- **Contrato V1, `/hello`, rotação de credencial.** O backend não fala nada
  disso (`player-mvp-contract.md`).
- **WebSocket, SSE, comandos remotos, telemetria sofisticada** (Sentry,
  analytics). O heartbeat de 15 s com `estado`/`erro`/`fila` é todo o
  sinal que o servidor recebe.
- **Relatório na TV** e **login de usuário** (este último vetado sempre).
- **Cache com eviction sofisticada.** ~~Teto de 1 GB, descarte do mais
  antigo.~~ **SUPERADA (02/10/2026):** o cache vive em `filesDir`, nunca
  apaga mídia referenciada pela playlist guardada nem pelo institucional, e
  o resto sai por LRU só para manter a reserva de disco (RN-07a). Continua
  sem categorias por anunciante, pré-alocação ou política por janela.

## Exceção de classificação (estação 1)

Este projeto **não é nenhum dos quatro tipos** que `classificar`/`construir`
enumeram (Institucional, SaaS, E-commerce, PWA) — é um app Android TV nativo,
sideload, sem interface web. Consequências assumidas:

- A estação 5 **não lê** `construir/references/desenvolvimento-web.md` nem
  `tipo-*.md` como checklist de fechamento — esses catorze itens (`<head>`,
  SEO técnico, formulário, e-mail, formato brasileiro) não existem neste
  produto. O checklist de fechamento da estação 5 é o mapa de blocos MVP do
  prompt original (seção 3), replicado em "Estado na esteira" do `CLAUDE.md`.
- **Cloudflare Access não se aplica.** Não há área administrativa no app; o
  único diálogo local é o PIN de saída.
- **"A versão inicial no ar" (fechamento da estação 5) não tem o mesmo
  sentido de URL que responde.** Para um app sideloaded, o equivalente é
  instalado e rodando de verdade num aparelho real. Esta sessão não tem
  acesso a hardware Android TV nem a um emulador viável (ambiente sem
  `/dev/kvm`, sem aceleração de virtualização — emulador Android TV sem
  aceleração é impraticável). A verificação "no ar" desta estação fica como
  pendência do dono, registrada em `docs/pendencias.md`, não como corte de
  canto desta sessão.

## Limites assumidos (Lei 7)

- **Fila de proof-of-play**: até 150.000 eventos pendentes por aparelho
  (`FilaProofOfPlay.TAMANHO_MAXIMO_FILA`; com o item mínimo de 5 s são 720
  por hora, ≈ 8,7 dias de tela 24 h offline, ~50–60 MB; com itens de 15 s,
  ≈ 26 dias; era 50.000). A exibição em andamento nunca é escolhida para
  descarte nem para a limpeza por idade. Acima disso, descarta o mais antigo, conta a perda
  e grava `FILA_CHEIA` no diário. Aviso no diário a partir de 10.000.
- **Lote de `/played`**: 50 eventos por requisição (o contrato aceita até
  500; lote pequeno mantém o corpo longe dos 100 KB).
- **Espera de reenvio**: 5 s → 15 s → 60 s → 5 min → 15 min → teto de 30 min,
  nunca desistência. 429 respeita `Retry-After` (1 s a 1 h).
- **Horizonte local**: ~~7 dias + 1 h para descartar pendente~~ —
  **SUPERADA (02/10/2026):** pendente fica até o ACK. O horizonte de 7 dias
  + 1 h vale só para o que não tem valor de cobrança (órfão, quarentena).
- **Cache de mídia**: sem teto fixo; reserva livre de max(512 MB, 10 % do
  disco), sem nunca apagar mídia referenciada (`CacheMidia`).
- **Segmentos operacionais** (05/10/2026; as "sessões" de 02/10 foram
  superadas pelo contrato do backend #114): saem com `ok`, `item_invalido`
  ou `ignorado`; pendentes ficam até a resposta; até 200 por lote; segmento
  de no máximo 6 h − 1 min; boot sem âncora do servidor é descartado.
- **Relógio de parede hostil**: espera de reenvio marcada mais de 31 min à
  frente é tratada como relógio que voltou (o comprovante sai); carimbo de
  comprovante e de segmento vem do relógio do servidor (âncora +
  monotônico), nunca da TV quando há âncora.
- **Download**: a reserva de disco é conferida antes (pelo
  `Content-Length`) e a cada 8 MB baixados; atingida, o download para e o
  parcial é apagado.
- **Watchdog**: retorno exato em 5/10/20/40/60 s depois de sair sem PIN;
  alarme de segurança de 60 s (até 16 min), 90 s sem sinal de vida reabre.
- **PIN de saída**: 3 erros → bloqueio de 5 s, dobrando até 5 min.

## Dependências (proporcionalidade, Lei 0)

Nenhuma dependência de rede HTTP nem de banco externo foi adicionada de
propósito: cliente HTTP é `HttpURLConnection` (JDK), a fila durável é SQLite
puro via `SQLiteOpenHelper` (sem Room). Menos dependência é menos risco de
resolução numa TV com internet de comércio, e o esquema é pequeno o
suficiente para não precisar de ORM. `kotlinx-coroutines-android` e
`androidx.lifecycle:lifecycle-runtime-ktx` foram aceitos por serem
ferramentas de concorrência padrão do ecossistema Android/Kotlin, não
dependências de negócio.

## Riscos de segurança aceitos

- **A chave do aparelho fica em texto claro no armazenamento privado do
  app.** Extraí-la exige acesso root ou físico à TV. A chave é revogável por
  tela no admin: o pior caso é uma tela, não a rede.
- **Só o release assinado com a chave definitiva vai a cliente**
  (`docs/release-producao.md`). O debug aceita HTTP em `127.0.0.1` (testes) e
  é assinado com a chave de depuração; o release é só HTTPS
  (`networkSecurityConfig`) e não entra em backup nem transferência entre
  aparelhos (`dataExtractionRules`).

## CI (estação 3)

Sem SAST nem scanner de dependência — desproporcional a um app sem dado
pessoal, sem pagamento e sem superfície web exposta (Lei 0). A cada push
(`.github/workflows/ci.yml`): varredura de segredos no conteúdo e no
histórico (`scripts/varrer-segredos.sh`), build debug e release,
conferência do APK (SDK, permissões, manifesto — `scripts/verificar-apk.sh`),
lint como portão (`abortOnError`) e testes unitários.
