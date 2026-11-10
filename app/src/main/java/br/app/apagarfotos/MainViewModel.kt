package br.app.apagarfotos

import android.Manifest
import android.app.Application
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tipo(val rotulo: String) { TUDO("Fotos e vídeos"), FOTOS("Só fotos"), VIDEOS("Só vídeos") }

class Mes(val chave: Int, val itens: List<MediaItem>) {
    val ano get() = chave / 100
    val numero get() = chave % 100
    val fotos = itens.count { !it.video }
    val videos = itens.size - fotos
    val bytes = itens.sumOf { it.tamanho }
}

val PERMISSOES: Array<String> = when {
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
    )
    Build.VERSION.SDK_INT >= 30 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )
}

/** Android 11+ tem lixeira e pede confirmação do sistema antes de apagar. */
val TEM_LIXEIRA = Build.VERSION.SDK_INT >= 30

/** Quantos arquivos vão em cada pedido de confirmação do Android. */
private const val LOTE = 1000

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication()

    var temPermissao by mutableStateOf(false); private set
    var carregando by mutableStateOf(false); private set
    var todos by mutableStateOf<List<MediaItem>>(emptyList()); private set
    var naLixeira by mutableStateOf<List<MediaItem>>(emptyList()); private set

    var tipo by mutableStateOf(Tipo.TUDO)
    var pasta by mutableStateOf<String?>(null)
    var mesesMarcados by mutableStateOf(setOf<Int>())
    var manter by mutableStateOf(setOf<Long>())
    var marcadosLixeira by mutableStateOf(setOf<Long>())

    var mesAberto by mutableStateOf<Int?>(null)
    var lixeiraAberta by mutableStateOf(false)
    var aviso by mutableStateOf<String?>(null)

    /** Quando não é nulo, a tela abre a confirmação do Android. */
    var pedido by mutableStateOf<IntentSender?>(null)
    var ocupado by mutableStateOf(false); private set

    val pastas: List<Pair<String, Int>> by derivedStateOf {
        todos.groupingBy { it.pasta }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key to it.value }
    }

    val meses: List<Mes> by derivedStateOf {
        val t = tipo
        val p = pasta
        todos.asSequence()
            .filter {
                when (t) {
                    Tipo.TUDO -> true
                    Tipo.FOTOS -> !it.video
                    Tipo.VIDEOS -> it.video
                }
            }
            .filter { p == null || it.pasta == p }
            .groupBy { it.mes }
            .map { (k, v) -> Mes(k, v) }
            .sortedByDescending { it.chave }
    }

    fun itensParaApagar(chaves: Set<Int>): List<MediaItem> =
        meses.filter { it.chave in chaves }.flatMap { it.itens }.filter { it.id !in manter }

    fun marcarMeses(chaves: List<Int>, marcar: Boolean) {
        mesesMarcados = if (marcar) mesesMarcados + chaves else mesesMarcados - chaves.toSet()
    }

    fun alternarManter(id: Long) {
        manter = if (id in manter) manter - id else manter + id
    }

    fun alternarLixeira(id: Long) {
        marcadosLixeira = if (id in marcadosLixeira) marcadosLixeira - id else marcadosLixeira + id
    }

    fun checarPermissao() {
        val ok = PERMISSOES.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
        val mudou = ok != temPermissao
        temPermissao = ok
        if (ok && (mudou || todos.isEmpty())) carregar()
    }

    fun carregar() {
        if (!temPermissao || carregando) return
        viewModelScope.launch {
            carregando = true
            val r = withContext(Dispatchers.IO) {
                runCatching { Galeria.carregar(ctx) to Galeria.carregar(ctx, lixeira = true) }
            }
            r.onSuccess { (lista, lixo) ->
                todos = lista
                naLixeira = lixo
                val ids = lista.mapTo(HashSet()) { it.id }
                manter = manter.filterTo(HashSet()) { it in ids }
                val idsLixo = lixo.mapTo(HashSet()) { it.id }
                marcadosLixeira = marcadosLixeira.filterTo(HashSet()) { it in idsLixo }
            }.onFailure { aviso = "Erro ao ler a galeria: ${it.message}" }
            carregando = false
        }
    }

    // ---- Apagar / lixeira / restaurar, em lotes ----

    private var fila = ArrayDeque<List<Uri>>()
    private var lote: List<Uri> = emptyList()
    private var acao = Acao.LIXEIRA
    private var feitos = 0

    fun executar(itens: List<MediaItem>, acaoEscolhida: Acao) {
        if (itens.isEmpty() || ocupado) return
        val uris = itens.map { it.uri }
        ocupado = true
        acao = acaoEscolhida
        feitos = 0
        if (TEM_LIXEIRA) {
            fila = ArrayDeque(uris.chunked(LOTE))
            proximoLote()
        } else {
            acao = Acao.APAGAR
            viewModelScope.launch {
                val n = withContext(Dispatchers.IO) { Galeria.apagarDireto(ctx, uris) }
                feitos = n
                terminar(falhas = uris.size - n, cancelado = false)
            }
        }
    }

    private fun proximoLote() {
        val l = fila.removeFirstOrNull() ?: run { terminar(0, false); return }
        lote = l
        try {
            pedido = Galeria.pedido(ctx, l, acao)
        } catch (e: Exception) {
            if (l.size > 50) {
                // Lote grande demais para o Android: divide em dois e tenta de novo.
                val meio = l.size / 2
                fila.addFirst(l.subList(meio, l.size))
                fila.addFirst(l.subList(0, meio))
                proximoLote()
            } else {
                aviso = "O Android recusou o pedido: ${e.message}"
                terminar(0, cancelado = true)
            }
        }
    }

    /** Resposta da janela de confirmação do Android. */
    fun resultadoPedido(confirmou: Boolean) {
        if (!ocupado) return
        if (confirmou) {
            feitos += lote.size
            proximoLote()
        } else {
            terminar(0, cancelado = true)
        }
    }

    private fun terminar(falhas: Int, cancelado: Boolean) {
        ocupado = false
        pedido = null
        val verbo = when (acao) {
            Acao.LIXEIRA -> "movidos para a lixeira"
            Acao.APAGAR -> "apagados"
            Acao.RESTAURAR -> "restaurados"
        }
        val msg = when {
            cancelado && feitos == 0 -> if (aviso != null) aviso else "Cancelado. Nada foi alterado."
            cancelado -> "Parou no meio: ${qtd(feitos, "item", "itens")} $verbo."
            falhas > 0 -> "${qtd(feitos, "item", "itens")} $verbo. $falhas não puderam ser apagados."
            else -> "Pronto! ${qtd(feitos, "item", "itens")} $verbo."
        }
        aviso = msg
        if (feitos > 0) {
            if (acao == Acao.RESTAURAR || lixeiraAberta) marcadosLixeira = emptySet()
            else mesesMarcados = emptySet()
        }
        carregar()
    }
}
