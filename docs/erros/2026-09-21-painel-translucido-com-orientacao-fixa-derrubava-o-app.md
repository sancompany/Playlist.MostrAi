# Painel translúcido com orientação fixa derrubava o app no Android 8.0

**Marcar como de ecossistema** — o padrão (tornar uma Activity translúcida
sem remover o `screenOrientation` que ela já tinha) não é específico deste
projeto: qualquer app que transforme uma Activity opaca em sobreposição
herda a mesma armadilha, e ela só aparece em runtime, na versão de Android
certa.

## O que aconteceu

A correção anterior (`2026-09-21-painel-parava-o-player-em-vez-de-so-cobrir.md`)
deu a `PainelActivity` um tema translúcido, pra ela cobrir o player em vez
de pará-lo. O que ninguém removeu foi a linha que já estava lá desde o
começo, no manifesto:

```xml
<activity
    android:name=".ui.PainelActivity"
    android:screenOrientation="landscape"        <!-- ← o problema -->
    android:theme="@style/Theme.MostraiPlayer.Translucido" />
```

No Android 8.0 (API 26), `Activity.onCreate` tem esta guarda:

```java
if (getApplicationInfo().targetSdkVersion >= O && mActivityInfo.isFixedOrientation()) {
    ...
    if (isTranslucentOrFloating) {
        throw new IllegalStateException(
                "Only fullscreen opaque activities can request orientation");
    }
}
```

Uma Activity translúcida **não pode** pedir orientação fixa: como dá pra
ver através dela, quem manda na orientação é a Activity opaca de trás. O
Android 8.0 não ignora o pedido — ele **lança exceção**. (Foi corrigido no
8.1, que passou a exigir `targetSdkVersion > 26` pra lançar; no 8.0 a
comparação é `>=`, então um app com `targetSdk = 26` estoura.)

Este projeto tem `minSdk = 26`, `targetSdk = 26`, e o parque instalado é
SEMP TCL 32S6500S **Android TV 8** (`CLAUDE.md`). Ou seja: as duas
condições batem exatamente. Abrir o painel de manutenção (3 toques de OK)
na TV da loja derrubaria o app com `IllegalStateException` antes mesmo do
teclado de PIN aparecer — e derrubar o app não é só perder o painel:
mata o player inteiro, a exibição paga em andamento, e deixa a TV com o
diálogo de "o app parou" na cara do cliente até o sistema reiniciar o
processo.

Pior detalhe: a correção anterior foi registrada como "sem como testar sem
aparelho real". Ela estava certa sobre isso — e o custo de não testar não
foi o comportamento sutil que se esperava (janela compondo direito), foi um
crash duro que nenhum teste unitário pega e que só a primeira visita à loja
mostraria.

## A regra que isso gerou

Tornar uma Activity translúcida é uma mudança de **contrato de janela**, não
um ajuste de estilo. Ao adicionar `windowIsTranslucent`, revisar tudo que o
manifesto declara sobre aquela Activity — `screenOrientation` em primeiro
lugar — porque atributos que eram inofensivos numa janela opaca passam a ser
proibidos. E o atalho comum ("tira do manifesto e chama
`setRequestedOrientation()` no código") **não resolve**: a mesma exceção é
lançada por ali. O único caminho é não pedir orientação nenhuma.

Vale também a lição mais geral: uma correção que a própria sessão classifica
como "não dá pra testar aqui" merece uma segunda leitura procurando o que ela
*arrasta junto*, não só se o efeito pretendido está certo.

## Onde foi corrigido

`AndroidManifest.xml`: `android:screenOrientation="landscape"` removido de
`PainelActivity`, com comentário explicando por que a ausência é
intencional (senão alguém "conserta" de volta). Não faz falta nenhuma —
janela translúcida herda a orientação da Activity opaca de trás, e
`PlayerActivity` continua travada em `landscape`. `PlayerActivity` é opaca,
então mantém o seu `screenOrientation` sem risco.

Sem teste automatizado: é validação de manifesto feita pelo framework em
runtime. A verificação em aparelho real (`docs/pendencias.md`) passa a
incluir explicitamente "abrir o painel não derruba o app".
