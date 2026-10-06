# Offline prolongado (Ponto Móvel) — o que o Player já faz e o que pede ao backend

> **CANCELADO (decisão do dono, 05/10/2026 — definitiva para a V1).** A
> Mostraí é *online-first*: pontos fixos usam a internet do
> estabelecimento; ponto móvel, hospedagem e eventos sem internet local usam
> dados móveis (hotspot, roteador 4G/5G). Offline é tolerância a
> interrupções temporárias, não modo de operação. O pacote offline de vários
> dias, o manifesto comercial de vários dias e a rota nova de backend
> propostos abaixo **não serão implementados**. Documento mantido só como
> histórico; a resiliência que já existe no Player (seção "O que já está no
> Player") continua valendo.

Pedido do dono de 02/10/2026 ("Master Player — cache e operação offline
prolongada para pontos móveis"). Regra-mestra: **offline não autoriza o
Player a inventar veiculação. O Player registra fatos; o backend decide
negócio.** O Player nunca calcula saldo, dívida, benefício, percentual de
hospedagem ou crédito.

## O que já está no Player (sem mudar o contrato de playlist)

| Peça | Como |
|---|---|
| Validade da programação | comercial só dentro de `janelaFim` da playlist guardada |
| Relógio confiável | âncora do servidor; após reboot, relógio da TV só se ≥ piso do servidor |
| Fallback | institucional da Mostraí já guardado, `contabiliza: false` |
| Prefetch | mídias da playlist e do institucional; com a programação vencida, só o institucional |
| Pronto para offline | bloco de suporte (só atrás do PIN): "PRONTO PARA OFFLINE ATÉ <validade>" ou "NÃO PRONTO: <motivo>" (sem instalação, sem programação válida, mídias incompletas, pouco espaço) |
| Cache | `filesDir`, SHA-256, download atômico, chaves protegidas, reserva max(512 MB, 10 %) |
| Proof-of-play | até o ACK, idempotente por `execucaoId`, lotes de 50, até 20 por rodada, backoff zerado na volta da rede |
| Tempo operacional | sessões por BOOT_COUNT + uptime, checkpoint de 30 s, `POST /player/:id/operacao` |

**Limite real hoje:** a TV fica "pronta para offline" só até o fim da hora
corrente, porque o backend entrega uma playlist por hora. Depois disso, só
institucional. Para um ponto móvel em evento de vários dias, isso não basta.

## O que precisa ser desenhado no backend (não implementado)

1. **Pacote offline** — `GET /player/:id/pacote-offline?horizonte=…`
   devolvendo uma lista de janelas `{janelaId, janelaInicio, janelaFim,
   itens[]}` já congeladas, como se o Player tivesse pedido cada hora. O
   horizonte é **configurável por tela/ponto** no admin (padrão sugerido
   7 dias; nunca 24/48 h fixo no código). Cada janela continua sendo a
   fonte do `janelaId` que o proof-of-play devolve — a validação do
   comprovante não muda.
2. **Contexto** (base / hospedagem / evento) mandado pelo backend dentro
   do pacote, com validade própria. O Player só repete o que recebeu e
   **nunca** troca de contexto sozinho: contexto vencido = só institucional.
3. **Assinatura do manifesto** (Ed25519 com a chave pública fixa no APK):
   impede que um arquivo trocado na TV vire programação. Hoje a integridade
   cobre só as mídias (SHA-256), não a lista.
4. **Reserva de mídia:** o pacote informa o tamanho total; o Player responde
   no heartbeat se coube (`prontoOfflineAte`, `midiasFaltando`) e o Admin
   mostra antes de a tela sair da base.
5. **Regras de negócio do offline** (todas do backend): se e como as horas
   exibidas offline entram no saldo, na obrigação e no benefício da
   hospedagem. O Player só manda os comprovantes e as sessões.

Até isso existir, o comportamento seguro é o atual: dentro da janela,
comercial; fora dela, institucional, sem comprovante.
