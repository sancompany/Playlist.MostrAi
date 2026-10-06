# ADR 0001 — targetSdk 36 com minSdk 26

- **Data:** 05/10/2026
- **Estado:** aceita (dono, decisões 1, 2 e 8 da finalização V1)
- **Contexto completo:** `docs/android-modernizacao.md`

## Contexto

O parque instalado do Mostraí é a SEMP TCL 32S6500S (Android TV 8.0, API
26). O Player 2.0.0 tinha `targetSdk 26`: correto para a TCL, mas em TVs
Android 14+ o sistema mostra o aviso "feito para uma versão antiga" sobre o
anúncio, e o projeto ficava preso a um toolchain que já não recebe
correções. A V1 de produção precisa rodar na TCL **e** não quebrar na
próxima TV que a operação comprar.

## Decisão

1. `minSdk 26` — a TCL continua sendo o piso.
2. `compileSdk 36` e `targetSdk 36` — o Android TV mais novo que existe
   (Android 16). Não 37: nenhuma TV roda API 37, e ele exige AGP 9.
3. Toolchain mínima que suporta 36 sem migrar para AGP 9: AGP 8.13.2,
   Kotlin 2.3.21, Robolectric 4.16.1, JDK 21 no CI.
4. `SYSTEM_ALERT_WINDOW` declarada — única exceção oficial ao bloqueio de
   abertura do segundo plano (Android 10+), necessária para o Player voltar
   sozinho à frente. Concedida manualmente na instalação; sem ela, o
   bloqueio é visível no bloco técnico e no diário.
5. Comportamentos do target 36 tratados no código: VOLTAR por
   `OnBackPressedCallback`, alarme `set` (não `setExact`) do Android 12 em
   diante, `networkSecurityConfig` só HTTPS, `dataExtractionRules`.
6. Versão 3.0.0, `versionCode 4`, assinada com a chave definitiva — a
   mesma em todas as atualizações daqui em diante.

## Consequências

- Na TCL (API 26) o comportamento é o mesmo da 2.0.0, salvo as correções
  desta versão.
- Em TVs Android 10+ o retorno automático depende de uma permissão especial
  concedida por pessoa — passo obrigatório do checklist de instalação.
- Media3 continua 1.4.1; subir exige teste na TCL (ver "O que ficou para
  depois" em `docs/android-modernizacao.md`).
- A migração para AGP 9 / API 37 é um passo separado, quando houver TV com
  Android 17.

## Alternativas descartadas

- **Manter target 26**: aviso de versão antiga no Android 14+, toolchain
  parada.
- **Target 37 com AGP 9**: migração de DSL e de Kotlin sem nenhuma TV para
  rodar.
- **`CATEGORY_HOME`**: o instalador da TCL recusa o APK.
- **Serviço em primeiro plano / Device Owner**: não resolvem o BAL (o
  primeiro) ou exigem reset e `adb` por TV (o segundo).
