package br.com.mostrai.player.atualizacao

import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Procura, copia e valida a atualização de um pendrive. Bloqueante — chamar
 * fora da thread principal (o vídeo segue tocando).
 *
 * Fluxo: localizar no pendrive → conferir espaço (a mesma reserva do cache
 * de mídia; nada é apagado para abrir espaço) → copiar para a área privada
 * calculando o SHA-256 → ler o APK copiado pelo `PackageManager` → validar
 * ([ValidacaoAtualizacao]). Só um APK aprovado fica em
 * `atualizacao/Mostrai-Player.apk`; qualquer outro resultado não deixa
 * arquivo nenhum. Depois de copiado, o pendrive pode sair: a instalação usa
 * a cópia.
 */
class VerificadorUsb(
    private val dirPrivado: File,
    private val leitor: LeitorDeApk,
    private val instalado: () -> InfoApk,
    private val certificadoOficial: String,
    private val espacoLivre: () -> Long,
    private val reserva: () -> Long,
) {
    sealed class Resultado(val mensagem: String) {
        object SemAtualizacao : Resultado("Pendrive sem atualização do Mostraí.")
        object SemEspaco : Resultado("Pouco espaço no aparelho para copiar a atualização.")
        object GrandeDemais : Resultado("APK do pendrive grande demais: recusado.")
        class FalhaLeitura(motivo: String) : Resultado("Não foi possível ler o pendrive ($motivo).")
        class Recusado(val veredito: ValidacaoAtualizacao.Veredito) : Resultado(veredito.mensagem)
        class Candidato(val apk: InfoApk, val arquivo: File, val sha256: String) :
            Resultado("Atualização ${apk.versionName ?: apk.versionCode} pronta para instalar.")
    }

    val arquivoFinal: File get() = File(dirPrivado, NOME_FINAL)

    fun verificar(origem: OrigemPacote): Resultado {
        val local = try {
            origem.localizar() ?: return Resultado.SemAtualizacao
        } catch (e: Exception) {
            return Resultado.FalhaLeitura(PENDRIVE_ILEGIVEL)
        }
        if (local.tamanho > TAMANHO_MAXIMO) return Resultado.GrandeDemais
        if (local.tamanho >= 0 && espacoLivre() - local.tamanho < reserva()) return Resultado.SemEspaco

        val (json, jsonIlegivel) = try {
            val texto = local.lerJson()
            if (texto == null) null to false else PacoteUsbJson.parse(texto).let { it to (it == null) }
        } catch (e: IOException) {
            return Resultado.FalhaLeitura(PENDRIVE_ILEGIVEL)
        } catch (e: RuntimeException) {
            // Pelo seletor do Android, pendrive que sumiu entre achar e abrir
            // vira IllegalArgumentException/SecurityException, não E/S.
            return Resultado.FalhaLeitura(PENDRIVE_ILEGIVEL)
        }

        dirPrivado.mkdirs()
        val temporario = File(dirPrivado, NOME_TEMPORARIO)
        temporario.delete()
        val sha256 = try {
            copiar(local, temporario)
        } catch (e: CopiaIncompleta) {
            temporario.delete()
            return Resultado.FalhaLeitura("cópia incompleta — pendrive removido?")
        } catch (e: IOException) {
            // Pendrive arrancado no meio, setor ruim: nada do parcial fica.
            // A mensagem da exceção (caminho do arquivo) não vai para a tela.
            temporario.delete()
            return Resultado.FalhaLeitura(PENDRIVE_ILEGIVEL)
        } catch (e: SemEspacoNaCopia) {
            temporario.delete()
            return Resultado.SemEspaco
        } catch (e: RuntimeException) {
            temporario.delete()
            return Resultado.FalhaLeitura(PENDRIVE_ILEGIVEL)
        }

        val veredito = ValidacaoAtualizacao.validar(
            candidato = leitor.ler(temporario),
            instalado = instalado(),
            certificadoOficial = certificadoOficial,
            sha256 = sha256,
            json = json,
            jsonIlegivel = jsonIlegivel,
        )
        if (veredito !is ValidacaoAtualizacao.Veredito.Disponivel) {
            temporario.delete()
            return Resultado.Recusado(veredito)
        }
        val final = arquivoFinal
        final.delete()
        if (!temporario.renameTo(final)) {
            temporario.delete()
            return Resultado.FalhaLeitura("não foi possível guardar a cópia")
        }
        return Resultado.Candidato(veredito.apk, final, sha256)
    }

    /** Apaga cópias de atualização (depois de instalada, ou para recomeçar). */
    fun limpar() {
        dirPrivado.listFiles()?.forEach { it.delete() }
    }

    private class SemEspacoNaCopia : RuntimeException()
    private class CopiaIncompleta : IOException()

    private fun copiar(local: OrigemPacote.Localizado, destino: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var copiados = 0L
        DigestInputStream(local.abrirApk(), digest).use { entrada ->
            destino.outputStream().use { saida ->
                val bloco = ByteArray(64 * 1024)
                while (true) {
                    val lidos = entrada.read(bloco)
                    if (lidos < 0) break
                    saida.write(bloco, 0, lidos)
                    copiados += lidos
                    if (copiados > TAMANHO_MAXIMO) throw CopiaIncompleta()
                    if (copiados % CONFERENCIA_DE_ESPACO_BYTES < lidos && espacoLivre() < reserva()) throw SemEspacoNaCopia()
                }
                saida.fd.sync()
            }
        }
        if (local.tamanho >= 0 && copiados != local.tamanho) throw CopiaIncompleta()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val NOME_FINAL = "Mostrai-Player.apk"
        const val PENDRIVE_ILEGIVEL = "pendrive removido ou ilegível"
        const val NOME_TEMPORARIO = "recebendo.apk"

        /** O APK do Player tem ~7 MB; 300 MB é folga, não meta. */
        const val TAMANHO_MAXIMO = 300L * 1024 * 1024

        const val CONFERENCIA_DE_ESPACO_BYTES = 8L * 1024 * 1024
    }
}
