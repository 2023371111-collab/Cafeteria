package com.example.cafeteria

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.delay

private const val SEGUNDOS_ACTUALIZAR = 15

// =============================================
// Pestaña "Barista" (solo cuentas con rol barista)
// =============================================

@Composable
fun BaristaScreen(baristaViewModel: BaristaViewModel, sesion: Sesion, chatViewModel: ChatViewModel? = null) {
    // Práctica 6: el barista cambia entre sus pedidos y la pila de cajones de avisos
    var vista by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        if (chatViewModel != null) {
            TabRow(selectedTabIndex = vista) {
                Tab(selected = vista == 0, onClick = { vista = 0 }, text = { Text("☕ Pedidos") })
                Tab(selected = vista == 1, onClick = { vista = 1 }, text = { Text("📥 Cajones") })
            }
        }
        if (vista == 1 && chatViewModel != null) CajonesPanel(chatViewModel, sesion) else PedidosBarista(baristaViewModel, sesion)
    }
}

@Composable
private fun PedidosBarista(baristaViewModel: BaristaViewModel, sesion: Sesion) {
    val estado by baristaViewModel.estado.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // Carga al abrir y luego se actualiza sola para ver los pedidos nuevos
    LaunchedEffect(sesion.token) {
        baristaViewModel.cargar(sesion.token)
        while (true) {
            delay(SEGUNDOS_ACTUALIZAR * 1000L)
            baristaViewModel.cargar(sesion.token, silencioso = true)
        }
    }
    LaunchedEffect(estado.mensaje) {
        estado.mensaje?.let {
            snackbar.showSnackbar(it)
            baristaViewModel.mensajeMostrado()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("☕ Pedidos por preparar", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Se actualiza cada $SEGUNDOS_ACTUALIZAR s", fontSize = 12.sp, color = Color.Gray)
                }
                TextButton(onClick = { baristaViewModel.cargar(sesion.token) }, enabled = !estado.cargando) {
                    Text("↻ Actualizar")
                }
            }

            val error = estado.error
            // Práctica 12 (RES-05): si la actualización falla (sesión vencida 401, sin red, servidor
            // ocupado) y ya hay una lista en pantalla, antes no se avisaba nada y el barista veía
            // pedidos viejos sin saberlo. Ahora se muestra el motivo encima de la lista.
            if (error != null && estado.pedidos.isNotEmpty()) {
                Text(
                    "📡 $error La lista puede estar desactualizada.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(10.dp)
                )
            }
            when {
                estado.cargando && estado.pedidos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && estado.pedidos.isEmpty() -> MessageState(
                    emoji = "📡",
                    title = "No se pudieron cargar los pedidos",
                    message = error,
                    actionLabel = "Reintentar",
                    onAction = { baristaViewModel.cargar(sesion.token) }
                )
                estado.pedidos.isEmpty() -> MessageState(
                    emoji = "🎉",
                    title = "Sin pedidos pendientes",
                    message = "Cuando un cliente envíe su carrito, su comanda aparece aquí."
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(estado.pedidos, key = { it.folio }) { pedido ->
                        PedidoBaristaCard(
                            pedido = pedido,
                            procesando = pedido.folio in estado.procesando,
                            onListo = { baristaViewModel.cambiarEstado(sesion.token, pedido, "listo") },
                            onEntregado = { baristaViewModel.cambiarEstado(sesion.token, pedido, "entregado") }
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PedidoBaristaCard(pedido: Order, procesando: Boolean, onListo: () -> Unit, onEntregado: () -> Unit) {
    val listo = pedido.status == "listo"

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = if (listo) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer) else CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(folioComanda(pedido), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    if (listo) "Listo" else "Por preparar",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Text(
                "${pedido.customer} · ${formatearFecha(pedido.createdAt)} · " +
                    if (pedido.avisable) "🔔 con aviso" else "🔕 sin aviso",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            pedido.items.forEach { item ->
                Text("${item.quantity} × ${item.name}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            if (pedido.notes.isNotBlank()) {
                Text("📝 ${pedido.notes}", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (pedido.latitude != null && pedido.longitude != null) {
                Text(
                    "📍 ${pedido.deliveryAddress ?: formatearCoordenadas(pedido.latitude, pedido.longitude)}",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (listo) {
                OutlinedButton(onClick = onEntregado, enabled = !procesando, modifier = Modifier.fillMaxWidth()) {
                    Text(if (procesando) "Guardando…" else "✅ Marcar entregado")
                }
            } else {
                Button(onClick = onListo, enabled = !procesando, modifier = Modifier.fillMaxWidth()) {
                    Text(if (procesando) "Avisando al cliente…" else "🔔 Pedido listo")
                }
            }
        }
    }
}
