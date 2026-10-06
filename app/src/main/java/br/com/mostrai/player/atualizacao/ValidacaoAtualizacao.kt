package br.com.mostrai.player.atualizacao

/**
 * Decide se o APK do pendrive é uma atualização legítima deste Player. Pura,
 * para teste. Nada aqui confia no nome do arquivo nem no update.json: o que
 * vale é o que o `PackageManager` leu do próprio APK.
 *
 * Ordem: APK legível → SHA-256 do update.json (se houver) → pacote →
 * assinatura (igual à do Player instalado **e** à chave definitiva
 * registrada) → coerência do update.json → versão (só sobe).
 */
object ValidacaoAtualizacao {

    sealed class Veredito(val mensagem: String) {
        class Disponivel(val apk: InfoApk) : Veredito("Atualização ${apk.versionName ?: apk.versionCode} disponível")
        object Corrompido : Veredito("APK inválido ou corrompido: recusado.")
        object HashErrado : Veredito("SHA-256 do APK não confere com o update.json: recusado.")
        object JsonIlegivel : Veredito("update.json ilegível: pacote recusado.")
        object JsonIncoerente : Veredito("update.json não confere com o APK: pacote recusado.")
        object PacoteErrado : Veredito("O APK do pendrive não é do Mostraí Player: ignorado.")
        object AssinaturaErrada : Veredito("APK do Mostraí com outra assinatura: recusado.")
        object PlayerForaDaChaveOficial : Veredito("Este Player não está assinado com a chave oficial: atualização por USB desligada.")
        object MesmaVersao : Veredito("Esta versão já está instalada.")
        object Anterior : Veredito("Versão do USB anterior à instalada.")
    }

    /**
     * @param candidato o que o Android leu do APK (`null` = não é APK).
     * @param sha256 do arquivo copiado, calculado durante a cópia.
     * @param json o update.json, se o pendrive tinha um; [JSON_ILEGIVEL] se tinha e não deu para ler.
     */
    fun validar(
        candidato: InfoApk?,
        instalado: InfoApk,
        certificadoOficial: String,
        sha256: String,
        json: PacoteUsbJson?,
        jsonIlegivel: Boolean = false,
    ): Veredito {
        val oficial = certificadoOficial.trim().lowercase()
        if (candidato == null) return Veredito.Corrompido
        if (jsonIlegivel) return Veredito.JsonIlegivel
        if (json?.sha256 != null && json.sha256 != sha256.lowercase()) return Veredito.HashErrado
        if (candidato.pacote != instalado.pacote) return Veredito.PacoteErrado
        if (instalado.certificados != setOf(oficial)) return Veredito.PlayerForaDaChaveOficial
        if (candidato.certificados != setOf(oficial)) return Veredito.AssinaturaErrada
        if (json?.certificateSha256 != null && json.certificateSha256 != oficial) return Veredito.JsonIncoerente
        if (json?.versionCode != null && json.versionCode != candidato.versionCode) return Veredito.JsonIncoerente
        return when {
            candidato.versionCode == instalado.versionCode -> Veredito.MesmaVersao
            candidato.versionCode < instalado.versionCode -> Veredito.Anterior
            else -> Veredito.Disponivel(candidato)
        }
    }
}
