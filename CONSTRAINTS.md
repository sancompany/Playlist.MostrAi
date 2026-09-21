# CONSTRAINTS — o que este projeto não faz

Escopo negativo e limites assumidos. Ver `docs/specs/2026-09-21-mostrai-player.md`
para o porquê de cada decisão de escopo.

## Vetos formais (não se negociam, ver `docs/specs/`)

- **Nunca usuário e senha na TV.** A tela se autentica só por chave de
  aparelho revogável, emitida no painel admin. Qualquer proposta de tela de
  login é rejeitada de saída.
- **Nenhum segredo versionado.** Chave, token, keystore de assinatura, URL de
  infraestrutura e credencial ficam fora do Git (`.gitignore`). O keystore de
  release fica com o dono, fora do repositório.
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

## Fora de escopo na v1 (veto desta versão, não "nunca")

- **Relatório na TV.** Sem tela de relatório/dashboard local — quem precisa
  de números usa o backend/admin.
- **Múltiplas orientações exóticas.** Só paisagem, fixo no manifesto.
- **Telemetria rica além do proof-of-play.** Sem analytics de uso, sem
  captura de erro com serviço de terceiro (Sentry etc.) nesta versão —
  desproporcional a um app sem dado pessoal e sem usuário interativo.
- **Atualização remota (OTA).** Item 7.3 em aberto no prompt original — as
  opções reais para Android TV 8 sideloaded ainda não foram levantadas.
- **Login de usuário.** Nenhum, por veto formal acima — não é "fora de
  escopo", é vetado permanentemente.
- **Cache de mídia com eviction sofisticada (LRU com métrica de acesso).** A
  v1 usa um teto de tamanho simples com descarte do mais antigo — ver
  `docs/funcional.md`, RN da seção de cache.

## Exceção de classificação (estação 1)

Este projeto **não é nenhum dos quatro tipos** que `classificar`/`construir`
enumeram (Institucional, SaaS, E-commerce, PWA) — é um app Android TV nativo,
sideload, sem interface web. Consequências assumidas:

- A estação 5 **não lê** `construir/references/desenvolvimento-web.md` nem
  `tipo-*.md` como checklist de fechamento — esses catorze itens (`<head>`,
  SEO técnico, formulário, e-mail, formato brasileiro) não existem neste
  produto. O checklist de fechamento da estação 5 é o mapa de blocos MVP do
  prompt original (seção 3), replicado em "Estado na esteira" do `CLAUDE.md`.
- **Cloudflare Access não se aplica.** O "painel de manutenção" é on-device,
  protegido por PIN local — não é área administrativa web.
- **"A versão inicial no ar" (fechamento da estação 5) não tem o mesmo
  sentido de URL que responde.** Para um app sideloaded, o equivalente é
  instalado e rodando de verdade num aparelho real. Esta sessão não tem
  acesso a hardware Android TV nem a um emulador viável (ambiente sem
  `/dev/kvm`, sem aceleração de virtualização — emulador Android TV sem
  aceleração é impraticável). A verificação "no ar" desta estação fica como
  pendência do dono, registrada em `docs/pendencias.md`, não como corte de
  canto desta sessão.

## Limites assumidos (Lei 7)

- **Fila de proof-of-play**: até 5.000 eventos pendentes por aparelho. Acima
  disso, descarta o mais antigo e conta a perda (visível no painel). Número
  do prompt original (seção 6.4), não medido — `// limite:` no código
  (`FilaProofOfPlay.TAMANHO_MAXIMO_FILA`) aponta para revisão se a operação
  real mostrar necessidade de mais.
- **Lote de envio de `/played`**: até 50 eventos por requisição — número do
  contrato (seção 6.2), não ajustável sem mudar o contrato do backend.
- **Backoff de reenvio**: 5s → 15s → 60s → 5min → 15min → teto de 30min,
  nunca desistência (seção 6.4).
- **Horizonte de expiração local**: 7 dias — depois disso um evento pendente
  é contado como perda e sai da fila (seção 6.5). Medido pelo relógio de
  parede do aparelho (não pelo monotônico): é contabilidade local de
  descarte, não decisão de negócio, então a proibição de depender do relógio
  não se aplica aqui — decisão registrada, não omissão.

## Dependências (proporcionalidade, Lei 0)

Nenhuma dependência de rede HTTP nem de banco externo foi adicionada de
propósito: cliente HTTP é `HttpURLConnection` (JDK), a fila durável é SQLite
puro via `SQLiteOpenHelper` (sem Room). Menos dependência é menos risco de
resolução numa TV com internet de comércio, e o esquema é pequeno o
suficiente para não precisar de ORM. `kotlinx-coroutines-android` e
`androidx.lifecycle:lifecycle-runtime-ktx` foram aceitos por serem
ferramentas de concorrência padrão do ecossistema Android/Kotlin, não
dependências de negócio.

## CI (estação 3)

Sem verificação automática de segurança dedicada (SAST, scanner de
dependência) nesta versão — desproporcional a um app sem dado pessoal, sem
pagamento e sem superfície web exposta (Lei 0). Mantido: build + testes
unitários automáticos a cada push (`.github/workflows/ci.yml`), que é o piso
que nenhum projeto pula.
