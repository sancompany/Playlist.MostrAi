# Player MVP — matriz contrato × Player

Fonte da verdade do protocolo: `sancompany/MostrAi` → `docs/player-mvp-contract.md`
(lido em 26/09/2026, commit `72931ae` do backend). Se este repositório
divergir dele, o contrato vence.

## Matriz inicial (Player em `cf3980e`, antes da reestruturação)

| Endpoint | Backend espera | Player atual envia | Player atual recebe | Ação necessária |
|---|---|---|---|---|
| `POST /player/provisionar` | `{codigoTela, codigoInstalacao}`; 200 `{dispositivoId, chaveAparelho}`; 400/401/429/5xx | `{tokenProvisionamento}` (token do pendrive) | `dispositivoId`, `chaveAparelho` | Trocar o corpo para ID da tela + código de instalação digitados na TV; tratar 400/401 (sem retentativa)/429 (`Retry-After`)/5xx (repetição curta) |
| `GET /playlist/:id` | header `X-Aparelho-Key`; envelope `versaoContrato: 2`; 401 = reinstalar; 403 = tela em reparo/inativa | `X-Aparelho-Id` + `X-Aparelho-Key`, `X-Player-Contract` | envelope **ou** array V1 (modo degradado) | Só envelope; 401 apaga a credencial; 403 para de exibir anúncios e mantém a fila |
| `POST /player/:id/played` | `{eventos: [...]}` até 500 (corpo ≤ 100 KB); 6 status finais; 400/413 bisseção; 401/403 mantém | lote V2 **e** formato legado `{anuncianteId}` | `resultados` **ou** `{contou}` legado | Só lote; 413 igual a 400; 401 também dispara reinstalação |
| `POST /player/:id/heartbeat` | a cada **15 s**; `{estado, configVersionAplicada, criativoId, erro, fila}`; resposta `{configVersion, playlist.atualizar}` | a cada 5 min; + `versaoContrato`, `ultimaPlaylistOkEm`, `desvioRelogioMs`, `update` | + `margens`, `update` (OTA), `novaChave`, `servidorAgora` | 15 s; só os 5 campos; resposta só `configVersion` e `playlist.atualizar` |
| `GET /player/:id/config` | `{configVersion, margens, operacao{timezone, porDiaDaSemana, feriados}, pinSaida}` | — | `configVersion`, `margens`, `rotacaoTela`, `operacao.regime`, `pinPainel`, `update`, `cache`, `versaoMinimaBuild` | Só os 4 campos; horário sem `regime`; `pinSaida` 4–8 dígitos ou `null` |
| `POST /player/:id/hello` | **não existe** | ficha técnica do aparelho | `configVersion` | Remover |
| download de APK (OTA) | **não existe** | `GET` na `url` do manifesto | APK | Remover |
