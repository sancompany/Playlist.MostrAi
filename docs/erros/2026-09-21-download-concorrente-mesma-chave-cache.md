# Download concorrente da mesma chave de cache corrompe o arquivo

**Marcar como de ecossistema** — qualquer cache de arquivo em disco que
baixa por chave (não por índice) tem esse risco se mais de um caminho de
código puder pedir a mesma chave ao mesmo tempo.

## O que aconteceu

`CacheMidia.resolver(item)` verificava se o arquivo já existia e, se não,
baixava para um arquivo temporário e renomeava. Sem sincronização. Dois
chamadores diferentes (o pré-aquecimento em segundo plano da playlist nova,
e a reprodução que acabou de chegar naquele mesmo item) podiam pedir a
mesma chave ao mesmo tempo — ambos passavam pela checagem "não existe"
antes de qualquer um terminar de baixar, e ambos escreviam no mesmo nome de
arquivo temporário simultaneamente, interleaving os bytes gravados.
Resultado: um vídeo corrompido persistido no cache, reproduzido depois como
lixo visual — sem nenhum erro visível no momento em que aconteceu.

## A regra que isso gerou

Cache por chave em disco (não em memória, onde a JVM ajudaria menos ainda)
precisa serializar o par checar-existe / baixar-e-renomear por chave, ou —
quando simplicidade importa mais que paralelismo (caso deste projeto,
internet de comércio já é o gargalo) — serializar globalmente. Nunca
assumir que "só um lugar chama isso" sem verificar todos os chamadores.

## Onde foi corrigido

`CacheMidia.kt` — `resolver()` marcado `@Synchronized`.
