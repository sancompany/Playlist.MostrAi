# Inventário de dados — Mostraí Player

## Nenhum dado pessoal é processado neste app

Este app não coleta, processa nem armazena dado de pessoa física. Registro
explícito, não ausência silenciosa (Lei 10).

**Por quê**: é um player de anúncio em quiosque (DOOH) sem interação do
espectador, sem câmera, sem sensor de presença, sem contagem de audiência.
Ninguém "usa" este app no sentido de ter uma conta, um login ou um perfil.

## O que o app de fato armazena e transmite

| Dado | O que é | De quem | Onde fica | Por quê |
|---|---|---|---|---|
| `dispositivoId` | Identificador da tela no cadastro do Mostraí | Ativo do negócio (a TV), não pessoa | `SharedPreferences` local + enviado ao servidor em toda chamada | Identificar qual tela está falando com o backend |
| `chaveAparelho` | Chave de autenticação revogável | Ativo do negócio | `SharedPreferences` local + header `X-Aparelho-Id` | Autenticação sem usuário/senha (veto formal) |
| `execucaoId`, `janelaId`, `itemProgramacaoId`, `criativoId` | Identificadores opacos de exibição de anúncio | Nenhuma pessoa — são identificadores de conteúdo publicitário e de janela de tempo | SQLite local (fila) + enviados ao servidor | Comprovante de que um anúncio passou, para cobrança do anunciante |
| `iniciadoEm`, `terminadoEm` | Timestamp de início/fim de uma exibição | Não é dado de pessoa — é dado operacional do aparelho | SQLite local + enviado ao servidor | Auditoria da exibição (nunca decide crédito, seção 6.2) |
| PIN do painel | 4 a 6 dígitos, configuração do aparelho | Não identifica pessoa — é segredo operacional compartilhado por quem opera aquela tela | `SharedPreferences` local, nunca enviado à rede | Proteger o painel de manutenção contra acesso casual |

Nenhuma dessas linhas identifica uma pessoa física, isolada ou em
combinação com outro dado que o app tenha acesso. `dispositivoId` e
`chaveAparelho` identificam um **aparelho** (ativo do comércio parceiro),
não um indivíduo.

## O que este app explicitamente NÃO coleta

- Nenhuma câmera, microfone ou sensor — não há contagem de audiência.
- Nenhum identificador de publicidade do Android (`AAID`) é lido.
- Nenhum dado de rede do visitante (não há Wi-Fi público operado por este
  app, não há captura de tráfego).
- Nenhum dado do dono do comércio além do que já está no cadastro do
  backend (fora deste repositório).

## Consequência para a skill `legal`

Como não há dado pessoal, os documentos legais que dependem de inventário de
dado de pessoa (Política de Privacidade orientada a titular, direitos de
titular no código — seção 8 de `docs/funcional.md`) **não se aplicam a este
app**. Se o Mostraí como negócio processa dado pessoal em outro lugar (ex.:
cadastro de anunciante, cadastro de comércio no admin), isso é inventário do
backend/admin (`sancompany/mostrai` ou equivalente), fora deste repositório.
