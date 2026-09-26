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
| `dispositivoId` (`M-0235`) | Código da tela no cadastro do Mostraí | Ativo do negócio (a TV), não pessoa | `SharedPreferences` privado + caminho de toda rota autenticada | Identificar qual tela fala com o backend |
| `chaveAparelho` | Credencial revogável da tela | Ativo do negócio | `SharedPreferences` privado + header `X-Aparelho-Key`; nunca em tela, log ou diário | Autenticação sem usuário/senha (veto formal) |
| Código de instalação | 8 caracteres, uso único, 30 min | Ativo do negócio | Só na memória, enquanto digitado; nunca gravado; apagado depois do sucesso | Trocar pela credencial |
| `execucaoId`, `janelaId`, `itemProgramacaoId`, `criativoId` | Identificadores opacos de exibição | Nenhuma pessoa | SQLite local (fila) + `/played` | Comprovante de exibição para cobrança |
| `iniciadoEm`, `terminadoEm` | Início/fim de uma exibição | Dado operacional do aparelho | SQLite local + `/played` | Auditoria (nunca decide crédito) |
| Config (margens, horário do ponto, `pinSaida`) | Configuração operacional | Do Mostraí | `SharedPreferences` privado | Aplicar offline |
| `pinSaida` | PIN global de saída, 4–8 dígitos | Segredo operacional compartilhado, não identifica pessoa | `SharedPreferences` privado (vem da config); nunca enviado de volta | Autorizar saída do app |
| Heartbeat (`estado`, `erro`, `fila`, versão do app) | Estado técnico da tela | Do aparelho | Enviado a cada 15 s; erro no diário local (SQLite) | Saúde da tela no admin |

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
titular no código — seção 7 de `docs/funcional.md`) **não se aplicam a este
app**. Se o Mostraí como negócio processa dado pessoal em outro lugar (ex.:
cadastro de anunciante, cadastro de comércio no admin), isso é inventário do
backend/admin (`sancompany/MostrAi`), fora deste repositório.
