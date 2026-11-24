package com.example.cafeteria

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

// =============================================
// Práctica 6 (MobileConnect): pantallas de avisos.
//  - ChatDialog: la conversación de un pedido (Dispositivo A = cliente, Dispositivo B = barista).
//  - CajonesPanel: la pila de cajones A, B y C del barista.
// =============================================

/** Cada cuánto se consultan los avisos nuevos del otro dispositivo. */
private const val SEGUNDOS_CONSULTA = 3

/** "2026-11-05 18:04:12" → "18:04" (hora de envío del aviso). */
fun horaDe(fecha: String): String = if (fecha.length >= 16) fecha.substring(11, 16) else fecha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDialog(chatViewModel: ChatViewModel) {
    val estado by chatViewModel.chat.collectAsState()
    if (!estado.abierta) return

    val snackbar = remember { SnackbarHostState() }
    var confirmarLimpiar by remember { mutableStateOf(false) }

    // Mientras la pantalla está abierta, consulta cada 3 s lo que mandó el otro dispositivo
    LaunchedEffect(estado.folio) {
        while (true) {
            delay(SEGUNDOS_CONSULTA * 1000L)
            chatViewModel.actualizar()
        }
    }
    // Notificación visual al enviar (o el motivo si falla)
    LaunchedEffect(estado.snackbar) {
        estado.snackbar?.let {
            snackbar.showSnackbar(it)
            chatViewModel.snackbarMostrado()
        }
    }

    if (confirmarLimpiar) {
        AlertDialog(
            onDismissRequest = { confirmarLimpiar = false },
            title = { Text("¿Limpiar historial?") },
            text = { Text("Los avisos se dejan de mostrar en este dispositivo. En la base de datos se conservan como historial del pedido.") },
            confirmButton = {
                TextButton(onClick = { confirmarLimpiar = false; chatViewModel.limpiarHistorial() }) { Text("Limpiar") }
            },
            dismissButton = { TextButton(onClick = { confirmarLimpiar = false }) { Text("Cancelar") } }
        )
    }

    Dialog(onDismissRequest = chatViewModel::cerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text("💬 Avisos · ${estado.folio}", fontSize = 18.sp) },
                    navigationIcon = { TextButton(onClick = chatViewModel::cerrar) { Text("← Volver") } },
                    actions = {
                        TextButton(onClick = { confirmarLimpiar = true }, enabled = estado.visibles.isNotEmpty()) {
                            Text("🧹 Limpiar")
                        }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize().imePadding()) {
                EncabezadoDispositivos(estado)
                SelectorEmisor(estado, chatViewModel::seleccionarEmisor)
                Text(
                    "Enviados — 📱 Cliente: ${estado.contador[Emisor.CLIENTE]} · ☕ Barista: ${estado.contador[Emisor.BARISTA]}",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp))

                ListaAvisos(estado, modifier = Modifier.weight(1f))

                if (estado.entregado) {
                    Text(
                        "✅ Pedido entregado: ya no admite avisos nuevos. El historial sigue visible.",
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(12.dp)
                    )
                } else {
                    Redactor(estado, chatViewModel)
                }
            }
        }
    }
}

/** Los dos dispositivos que se comunican: el cliente y el barista con su cajón. */
@Composable
private fun EncabezadoDispositivos(estado: ChatUiState) {
    val conv = estado.conversacion
    val barista = when {
        conv == null -> "…"
        conv.cajon != null -> "Cajón ${conv.cajon}"
        conv.posicionEspera != null -> "⏳ En espera, posición ${conv.posicionEspera}"
        else -> "sin cajón (se asigna con el primer aviso)"
    }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("📱 Dispositivo A · Cliente: ${conv?.cliente ?: "…"} (${estado.folio})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("☕ Dispositivo B · Barista: $barista", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            estado.error?.let { Text("📡 $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectorEmisor(estado: ChatUiState, onSeleccionar: (Emisor) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text("Dispositivo emisor", fontSize = 12.sp, color = Color.Gray)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            Emisor.entries.forEachIndexed { i, emisor ->
                val habilitado = if (emisor == Emisor.CLIENTE) estado.puedeSerCliente else estado.puedeSerBarista
                SegmentedButton(
                    selected = estado.emisor == emisor,
                    onClick = { onSeleccionar(emisor) },
                    enabled = habilitado,
                    shape = SegmentedButtonDefaults.itemShape(i, Emisor.entries.size)
                ) {
                    Text("${emisor.icono} ${emisor.etiqueta}")
                }
            }
        }
        if (!estado.puedeSerBarista) {
            Text("Solo una cuenta de barista puede enviar como Barista.", fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun ListaAvisos(estado: ChatUiState, modifier: Modifier) {
    val lista = rememberLazyListState()
    val visibles = estado.visibles
    LaunchedEffect(visibles.size) {
        if (visibles.isNotEmpty()) lista.animateScrollToItem(visibles.size - 1)
    }
    if (estado.cargando && visibles.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (visibles.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                if (estado.ocultosHasta > 0) "Historial limpio. Los avisos nuevos aparecerán aquí."
                else "Aún no hay avisos de este pedido.",
                color = Color.Gray
            )
        }
        return
    }
    LazyColumn(
        state = lista,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        items(visibles, key = { it.id }) { mensaje ->
            BurbujaAviso(mensaje, propio = mensaje.emisor == estado.emisor)
        }
    }
}

@Composable
private fun BurbujaAviso(mensaje: Mensaje, propio: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (propio) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (propio) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("${mensaje.emisor.icono} ${mensaje.emisor.etiqueta}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(mensaje.texto, fontSize = 15.sp)
            Text(
                horaDe(mensaje.creadoEn) + (mensaje.cajon?.let { " · cajón $it" } ?: " · en espera"),
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
private fun Redactor(estado: ChatUiState, chatViewModel: ChatViewModel) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NOTAS_RAPIDAS.getValue(estado.emisor).forEach { nota ->
                AssistChip(onClick = { chatViewModel.usarNota(nota) }, label = { Text(nota, fontSize = 12.sp) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = estado.texto,
                onValueChange = chatViewModel::onTextoChange,
                placeholder = { Text("Escribe un aviso como ${estado.emisor.etiqueta}…") },
                supportingText = {
                    Text(
                        if (estado.texto.isBlank()) "El botón se activa al escribir (los espacios no cuentan)"
                        else "${estado.texto.trim().length}/$MAX_TEXTO_AVISO"
                    )
                },
                maxLines = 3,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = chatViewModel::enviar, enabled = estado.puedeEnviar) {
                Text(if (estado.enviando) "…" else "Enviar")
            }
        }
    }
}

// =============================================
// Pila de cajones del barista
// =============================================

@Composable
fun CajonesPanel(chatViewModel: ChatViewModel, sesion: Sesion) {
    val estado by chatViewModel.cajones.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(sesion.token) {
        chatViewModel.cargarCajones(sesion.token)
        while (true) {
            delay(SEGUNDOS_CONSULTA * 1000L)
            chatViewModel.cargarCajones(sesion.token, silencioso = true)
        }
    }
    LaunchedEffect(estado.mensaje) {
        estado.mensaje?.let {
            snackbar.showSnackbar(it)
            chatViewModel.mensajeCajonesMostrado()
        }
    }

    Box(Modifier.fillMaxSize()) {
        val pila = estado.pila
        when {
            pila == null && estado.error != null -> MessageState(
                emoji = "📡", title = "No se pudieron cargar los cajones", message = estado.error!!,
                actionLabel = "Reintentar", onAction = { chatViewModel.cargarCajones(sesion.token) }
            )
            pila == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Text("📥 Cajones de avisos", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Arriba los ocupados (el más antiguo primero), abajo los libres. Se actualiza cada $SEGUNDOS_CONSULTA s.",
                        fontSize = 12.sp, color = Color.Gray)
                }
                items(pila.cajones, key = { it.letra }) { cajon ->
                    CajonCard(
                        cajon = cajon,
                        liberando = estado.liberando == cajon.letra,
                        onAbrir = { cajon.folio?.let { chatViewModel.abrir(it, null, sesion.token) } },
                        onLiberar = { chatViewModel.liberar(sesion.token, cajon.letra) }
                    )
                }
                item {
                    Text("⏳ En espera (${pila.espera.size})", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    if (pila.espera.isEmpty()) Text("Nadie esperando.", color = Color.Gray, fontSize = 13.sp)
                }
                items(pila.espera.size) { i ->
                    val e = pila.espera[i]
                    Text("${i + 1}. ${e.folio} · ${e.cliente} · desde ${horaDe(e.desde)}", fontSize = 14.sp)
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun CajonCard(cajon: Cajon, liberando: Boolean, onAbrir: () -> Unit, onLiberar: () -> Unit) {
    Card(
        colors = if (cajon.libre) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                 else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                cajon.letra,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(44.dp)
            )
            if (cajon.libre) {
                Text("Libre", color = Color.Gray, modifier = Modifier.weight(1f))
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${cajon.folio} · ${cajon.cliente}", fontWeight = FontWeight.SemiBold)
                    Text("${cajon.totalMensajes} aviso(s) · se libera en ${cajon.minutosRestantes} min sin avisos",
                        fontSize = 12.sp, color = Color.Gray)
                    cajon.ultimoMensaje?.let { Text("“$it”", fontSize = 12.sp, maxLines = 1) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Button(onClick = onAbrir, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("💬 Abrir") }
                        OutlinedButton(onClick = onLiberar, enabled = !liberando, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Text(if (liberando) "Liberando…" else "🔓 Liberar cajón")
                        }
                    }
                }
            }
        }
    }
}
