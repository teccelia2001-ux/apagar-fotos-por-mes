@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package br.app.apagarfotos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Tema { App(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.checarPermissao()
    }
}

// ---------- Textos ----------

private val NOMES_MES = arrayOf(
    "Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho",
    "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro",
)
private val VERMELHO = Color(0xFFC62828)
private val VERDE = Color(0xFF2E7D32)

fun nomeMes(chave: Int) = "${NOMES_MES[chave % 100 - 1]} de ${chave / 100}"
fun mesCurto(chave: Int) = "${NOMES_MES[chave % 100 - 1].take(3)}/${chave / 100}"
fun qtd(n: Int, um: String, varios: String) = "$n ${if (n == 1) um else varios}"
fun resumo(fotos: Int, videos: Int): String = listOfNotNull(
    if (fotos > 0) qtd(fotos, "foto", "fotos") else null,
    if (videos > 0) qtd(videos, "vídeo", "vídeos") else null,
).joinToString(" e ").ifEmpty { "nada" }

fun tamanho(ctx: Context, bytes: Long): String = Formatter.formatShortFileSize(ctx, bytes)

fun resumoItens(ctx: Context, itens: List<MediaItem>): String {
    val fotos = itens.count { !it.video }
    return "${resumo(fotos, itens.size - fotos)} · ${tamanho(ctx, itens.sumOf { it.tamanho })}"
}

fun abrirConfiguracoes(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

fun abrirMidia(ctx: Context, item: MediaItem) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(item.uri, if (item.video) "video/*" else "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    } catch (_: Exception) {
    }
}

// ---------- Estrutura ----------

@Composable
fun Tema(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val escuro = isSystemInDarkTheme()
    val cores = when {
        Build.VERSION.SDK_INT >= 31 -> if (escuro) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        escuro -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = cores, content = content)
}

@Composable
fun App(vm: MainViewModel) {
    val ctx = LocalContext.current
    val pedirPermissao = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.checarPermissao() }
    val confirmacaoAndroid = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { vm.resultadoPedido(it.resultCode == Activity.RESULT_OK) }

    val pedido = vm.pedido
    LaunchedEffect(pedido) {
        if (pedido != null) {
            vm.pedido = null
            confirmacaoAndroid.launch(IntentSenderRequest.Builder(pedido).build())
        }
    }

    var jaPediu by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        vm.checarPermissao()
        if (!vm.temPermissao && !jaPediu) {
            jaPediu = true
            pedirPermissao.launch(PERMISSOES)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !vm.temPermissao -> TelaPermissao(
                onPedir = { pedirPermissao.launch(PERMISSOES) },
                onConfig = { abrirConfiguracoes(ctx) },
            )
            vm.lixeiraAberta -> TelaLixeira(vm)
            vm.mesAberto != null -> TelaMes(vm)
            else -> TelaMeses(vm)
        }
    }

    vm.aviso?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.aviso = null },
            confirmButton = { TextButton(onClick = { vm.aviso = null }) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}

@Composable
fun TelaPermissao(onPedir: () -> Unit, onConfig: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Warning, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            "Preciso ver suas fotos e vídeos",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Para separar a galeria por mês, o app precisa de acesso a TODAS as fotos e vídeos. " +
                "Quando o Android perguntar, escolha \"Permitir tudo\" (não \"Selecionar fotos\").",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPedir) { Text("Dar permissão") }
        TextButton(onClick = onConfig) { Text("Abrir configurações do app") }
    }
}

// ---------- Tela principal: lista de meses ----------

@Composable
fun TelaMeses(vm: MainViewModel) {
    val ctx = LocalContext.current
    var confirmar by remember { mutableStateOf(false) }
    val meses = vm.meses
    val paraApagar = vm.itensParaApagar(vm.mesesMarcados)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apagar fotos por mês") },
                actions = {
                    if (TEM_LIXEIRA) {
                        TextButton(onClick = { vm.lixeiraAberta = true }) {
                            Text("Lixeira (${vm.naLixeira.size})")
                        }
                    }
                    IconButton(onClick = { vm.carregar() }) { Icon(Icons.Default.Refresh, "Atualizar") }
                },
            )
        },
        bottomBar = {
            if (paraApagar.isNotEmpty()) {
                val n = vm.mesesMarcados.count { k -> meses.any { it.chave == k } }
                BarraAcao(
                    titulo = resumoItens(ctx, paraApagar),
                    detalhe = qtd(n, "mês marcado", "meses marcados"),
                    botao = "Apagar",
                    habilitado = !vm.ocupado,
                    onClick = { confirmar = true },
                )
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.carregando) LinearProgressIndicator(Modifier.fillMaxWidth())
            Filtros(vm)
            when {
                vm.carregando && vm.todos.isEmpty() -> Centro { Text("Lendo a galeria…") }
                meses.isEmpty() -> Centro { Text("Nenhuma foto ou vídeo encontrado.") }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    meses.groupBy { it.ano }.forEach { (ano, doAno) ->
                        item(key = "ano-$ano") {
                            val todosMarcados = doAno.all { it.chave in vm.mesesMarcados }
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(start = 16.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "$ano",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { vm.marcarMeses(doAno.map { it.chave }, !todosMarcados) }) {
                                    Text(if (todosMarcados) "Desmarcar o ano" else "Marcar o ano todo")
                                }
                            }
                        }
                        items(doAno, key = { it.chave }) { mes -> LinhaMes(mes, vm) }
                    }
                }
            }
        }
    }

    if (confirmar) {
        DialogoApagar(paraApagar, onCancelar = { confirmar = false }) { acao ->
            confirmar = false
            vm.executar(paraApagar, acao)
        }
    }
}

@Composable
fun Centro(conteudo: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { conteudo() }
}

@Composable
fun Filtros(vm: MainViewModel) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tipo.entries.forEach { t ->
                FilterChip(selected = vm.tipo == t, onClick = { vm.tipo = t }, label = { Text(t.rotulo) })
            }
            Box {
                AssistChip(
                    onClick = { menu = true },
                    label = {
                        Text(
                            vm.pasta ?: "Todas as pastas",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                    },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Todas as pastas") },
                        onClick = { vm.pasta = null; menu = false },
                    )
                    vm.pastas.forEach { (nome, n) ->
                        DropdownMenuItem(
                            text = { Text("$nome ($n)") },
                            onClick = { vm.pasta = nome; menu = false },
                        )
                    }
                }
            }
        }
        val itens = vm.meses.flatMap { it.itens }
        Text(
            "Total na galeria: ${resumoItens(ctx, itens)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Marque os meses e toque em Apagar. Toque num mês para ver as fotos e escolher o que manter.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

@Composable
fun LinhaMes(mes: Mes, vm: MainViewModel) {
    val ctx = LocalContext.current
    val marcado = mes.chave in vm.mesesMarcados
    val mantidos = mes.itens.count { it.id in vm.manter }
    Row(
        Modifier.fillMaxWidth()
            .clickable { vm.mesAberto = mes.chave }
            .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = marcado, onCheckedChange = { vm.marcarMeses(listOf(mes.chave), it) })
        Column(Modifier.weight(1f)) {
            Text(NOMES_MES[mes.numero - 1], style = MaterialTheme.typography.titleMedium)
            Text(
                "${resumo(mes.fotos, mes.videos)} · ${tamanho(ctx, mes.bytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (mantidos > 0) {
                Text(
                    "${qtd(mantidos, "item", "itens")} para manter",
                    style = MaterialTheme.typography.bodySmall,
                    color = VERDE,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        mes.itens.take(3).forEach {
            Miniatura(it, Modifier.padding(start = 4.dp).size(44.dp).clip(RoundedCornerShape(6.dp)))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

// ---------- Tela de um mês ----------

@Composable
fun TelaMes(vm: MainViewModel) {
    val ctx = LocalContext.current
    val mes = vm.meses.firstOrNull { it.chave == vm.mesAberto }
    if (mes == null) {
        LaunchedEffect(Unit) { vm.mesAberto = null }
        return
    }
    BackHandler { vm.mesAberto = null }
    var confirmar by remember { mutableStateOf(false) }
    val paraApagar = mes.itens.filter { it.id !in vm.manter }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.mesAberto = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
                title = {
                    Column {
                        Text(nomeMes(mes.chave))
                        Text(
                            "${resumo(mes.fotos, mes.videos)} · ${tamanho(ctx, mes.bytes)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (paraApagar.isNotEmpty()) {
                BarraAcao(
                    titulo = resumoItens(ctx, paraApagar),
                    detalhe = "${paraApagar.size} de ${mes.itens.size} serão apagados",
                    botao = "Apagar",
                    habilitado = !vm.ocupado,
                    onClick = { confirmar = true },
                )
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                "Toque numa foto para MANTER (não apagar). Toque e segure para abrir.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(Modifier.padding(horizontal = 4.dp)) {
                TextButton(onClick = { vm.manter = vm.manter + mes.itens.map { it.id } }) { Text("Manter todas") }
                TextButton(onClick = { vm.manter = vm.manter - mes.itens.map { it.id }.toSet() }) { Text("Apagar todas") }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(2.dp),
            ) {
                items(mes.itens, key = { it.id }) { item ->
                    val manter = item.id in vm.manter
                    Celula(
                        item,
                        onClick = { vm.alternarManter(item.id) },
                        onLongClick = { abrirMidia(ctx, item) },
                    ) {
                        if (manter) {
                            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f)))
                            Column(
                                Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF81C784))
                                Text("Manter", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Icon(
                                Icons.Default.Delete, null, tint = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                    .size(22.dp).background(VERMELHO.copy(alpha = 0.85f), CircleShape).padding(3.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmar) {
        DialogoApagar(paraApagar, onCancelar = { confirmar = false }) { acao ->
            confirmar = false
            vm.executar(paraApagar, acao)
        }
    }
}

// ---------- Lixeira (Android 11+) ----------

@Composable
fun TelaLixeira(vm: MainViewModel) {
    val ctx = LocalContext.current
    BackHandler { vm.lixeiraAberta = false }
    val itens = vm.naLixeira
    val marcados = itens.filter { it.id in vm.marcadosLixeira }
    var apagarDeVez by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.lixeiraAberta = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                    }
                },
                title = { Text("Lixeira") },
            )
        },
        bottomBar = {
            if (marcados.isNotEmpty()) {
                Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(resumoItens(ctx, marcados), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.executar(marcados, Acao.RESTAURAR) },
                                enabled = !vm.ocupado,
                                modifier = Modifier.weight(1f),
                            ) { Text("Restaurar") }
                            Button(
                                onClick = { apagarDeVez = true },
                                enabled = !vm.ocupado,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = VERMELHO, contentColor = Color.White),
                            ) { Text("Apagar de vez") }
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                "Itens na lixeira são apagados de vez pelo Android depois de 30 dias. " +
                    "Toque para selecionar; toque e segure para abrir.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (itens.isEmpty()) {
                Centro { Text("A lixeira está vazia.") }
                return@Column
            }
            Row(Modifier.padding(horizontal = 4.dp)) {
                TextButton(onClick = { vm.marcadosLixeira = itens.mapTo(HashSet()) { it.id } }) { Text("Selecionar tudo") }
                TextButton(onClick = { vm.marcadosLixeira = emptySet() }) { Text("Limpar seleção") }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(2.dp),
            ) {
                items(itens, key = { it.id }) { item ->
                    val marcado = item.id in vm.marcadosLixeira
                    Celula(item, onClick = { vm.alternarLixeira(item.id) }, onLongClick = { abrirMidia(ctx, item) }) {
                        if (marcado) {
                            Box(
                                Modifier.matchParentSize()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                            )
                            Icon(
                                Icons.Default.CheckCircle, null, tint = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (apagarDeVez) {
        AlertDialog(
            onDismissRequest = { apagarDeVez = false },
            icon = { Icon(Icons.Default.Delete, null, tint = VERMELHO) },
            title = { Text("Apagar de vez ${qtd(marcados.size, "item", "itens")}?") },
            text = { Text("${resumoItens(ctx, marcados)}.\n\nIsto NÃO tem como desfazer.") },
            confirmButton = {
                TextButton(onClick = { apagarDeVez = false; vm.executar(marcados, Acao.APAGAR) }) {
                    Text("Apagar de vez", color = VERMELHO, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { apagarDeVez = false }) { Text("Cancelar") } },
        )
    }
}

// ---------- Peças reutilizadas ----------

@Composable
fun BarraAcao(titulo: String, detalhe: String, botao: String, habilitado: Boolean, onClick: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(titulo, fontWeight = FontWeight.Bold)
                Text(detalhe, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onClick,
                enabled = habilitado,
                colors = ButtonDefaults.buttonColors(containerColor = VERMELHO, contentColor = Color.White),
            ) {
                Icon(Icons.Default.Delete, null)
                Spacer(Modifier.width(6.dp))
                Text(botao)
            }
        }
    }
}

@Composable
fun DialogoApagar(itens: List<MediaItem>, onCancelar: () -> Unit, onConfirmar: (Acao) -> Unit) {
    val ctx = LocalContext.current
    var lixeira by remember { mutableStateOf(true) }
    val meses = itens.map { it.mes }.distinct().sortedDescending()
    val listaMeses = meses.take(6).joinToString(", ") { mesCurto(it) } +
        if (meses.size > 6) " e mais ${meses.size - 6}" else ""
    AlertDialog(
        onDismissRequest = onCancelar,
        icon = { Icon(Icons.Default.Delete, null, tint = VERMELHO) },
        title = { Text("Apagar ${qtd(itens.size, "item", "itens")}?") },
        text = {
            Column {
                Text("${resumoItens(ctx, itens)}\nDe: $listaMeses.")
                Spacer(Modifier.height(12.dp))
                if (TEM_LIXEIRA) {
                    Row(
                        Modifier.clickable { lixeira = !lixeira },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = lixeira, onCheckedChange = { lixeira = it })
                        Text(
                            "Mandar para a lixeira (dá para restaurar pelo botão Lixeira; " +
                                "o Android apaga de vez em 30 dias)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!lixeira) {
                        Text(
                            "Sem lixeira não tem como desfazer!",
                            color = VERMELHO,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Em seguida o Android vai pedir para você confirmar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("Atenção: neste Android não existe lixeira. Não tem como desfazer.", color = VERMELHO)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirmar(if (TEM_LIXEIRA && lixeira) Acao.LIXEIRA else Acao.APAGAR) }) {
                Text("Apagar", color = VERMELHO, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } },
    )
}

@Composable
fun Celula(
    item: MediaItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    sobreposicao: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier.padding(2.dp).aspectRatio(1f).clip(RoundedCornerShape(4.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Miniatura(item, Modifier.fillMaxSize())
        if (item.video) {
            Icon(
                Icons.Default.PlayArrow, null, tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            )
        }
        sobreposicao()
    }
}

@Composable
fun Miniatura(item: MediaItem, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(Galeria.miniaturaEmCache(item), item.id) {
        if (value == null) value = withContext(Dispatchers.IO) { Galeria.miniatura(ctx, item) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let {
            Image(
                it.asImageBitmap(), null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
