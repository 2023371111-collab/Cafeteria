package com.example.cafeteria

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** "2026-10-12 19:05:00" (formato de MySQL) -> "12/10/2026 19:05". */
fun formatearFecha(fecha: String): String {
    val partes = Regex("""(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})""").find(fecha) ?: return fecha
    val (anio, mes, dia, hora, minuto) = partes.destructured
    return "$dia/$mes/$anio $hora:$minuto"
}

/** 20.588793, -100.389888 -> "20.58879, -100.38989" (siempre con punto decimal). */
fun formatearCoordenadas(latitud: Double, longitud: Double): String =
    String.format(Locale.US, "%.5f, %.5f", latitud, longitud)

// =============================================
// Captura y envío: formulario "Confirmar pedido"
// =============================================

@Composable
fun CheckoutScreen(
    cartItems: List<CartItem>,
    ordersViewModel: OrdersViewModel,
    onVolver: () -> Unit
) {
    val estado by ordersViewModel.checkout.collectAsState()
    val foco = LocalFocusManager.current
    val totalEstimado = cartItems.sumOf { it.product.price * it.quantity }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        TextButton(onClick = onVolver, enabled = !estado.enviando, contentPadding = PaddingValues(0.dp)) {
            Text("← Volver al carrito")
        }
        Text("Confirmar pedido", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        // Resumen de lo que se va a pedir
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                cartItems.forEach { item ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${item.quantity} × ${item.product.name}", fontSize = 14.sp)
                        Text(formatPrice(item.product.price * item.quantity), fontSize = 14.sp)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total estimado", fontWeight = FontWeight.Bold)
                    Text(formatPrice(totalEstimado), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Text("El total final lo calcula el servidor con los precios vigentes.", fontSize = 11.sp, color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Tus datos", fontSize = 16.sp, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = estado.nombre,
            onValueChange = ordersViewModel::onNombreChange,
            label = { Text("Nombre") },
            singleLine = true,
            enabled = !estado.enviando,
            isError = estado.errorNombre != null,
            supportingText = { Text(estado.errorNombre ?: "Para llamarte cuando esté listo") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = estado.telefono,
            onValueChange = ordersViewModel::onTelefonoChange,
            label = { Text("Teléfono") },
            singleLine = true,
            enabled = !estado.enviando,
            isError = estado.errorTelefono != null,
            supportingText = { Text(estado.errorTelefono ?: "${estado.telefono.length}/10 dígitos · con él consultas tus pedidos") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = estado.notas,
            onValueChange = ordersViewModel::onNotasChange,
            label = { Text("Notas (opcional)") },
            placeholder = { Text("Ej. sin azúcar, leche deslactosada") },
            enabled = !estado.enviando,
            isError = estado.errorNotas != null,
            supportingText = { Text(estado.errorNotas ?: "${estado.notas.length}/${ValidadorPedido.MAX_NOTAS}") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { foco.clearFocus() }),
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))
        UbicacionEntrega(
            estado = estado,
            onUsarUbicacion = ordersViewModel::obtenerUbicacion,
            onElegirEnMapa = ordersViewModel::elegirEnMapa,
            onPermisoDenegado = ordersViewModel::onPermisoUbicacionDenegado,
            onQuitar = ordersViewModel::quitarUbicacion
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Error al enviar (sin internet, servidor caído, producto agotado...)
        estado.errorEnvio?.let { mensaje ->
            Text(
                "📡 $mensaje",
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(12.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Android 13+: se pide permiso de notificaciones al enviar, para avisar cuando esté listo.
        // Conteste lo que conteste, el pedido se envía.
        val context = LocalContext.current
        val pedirNotificaciones = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            ordersViewModel.enviarPedido(cartItems)
        }
        Text(
            "🔔 Te avisamos en tu teléfono y en tu reloj cuando el pedido esté listo.",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(
            onClick = {
                foco.clearFocus()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !NotificacionesPedido.tienePermiso(context)) {
                    pedirNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    ordersViewModel.enviarPedido(cartItems)
                }
            },
            enabled = !estado.enviando,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            if (estado.enviando) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                // Práctica 12: si hay reintento automático se muestra cuál va ("Reintentando (2 de 3)…")
                Text(estado.mensajeReintento ?: "Enviando pedido…", fontSize = 16.sp)
            } else {
                Text("☁️ Enviar pedido", fontSize = 18.sp)
            }
        }
    }
}

/**
 * Ubicación de entrega (opcional). El permiso se pide aquí, al tocar el botón,
 * y no al abrir la app, para que el usuario sepa para qué se usa.
 */
@Composable
private fun UbicacionEntrega(
    estado: CheckoutUiState,
    onUsarUbicacion: () -> Unit,
    onElegirEnMapa: (Double, Double) -> Unit,
    onPermisoDenegado: () -> Unit,
    onQuitar: () -> Unit
) {
    val context = LocalContext.current
    var mostrandoMapa by remember { mutableStateOf(false) }

    if (mostrandoMapa) {
        SelectorMapaDialog(
            inicial = estado.ubicacion,
            onElegir = { lat, lng ->
                mostrandoMapa = false
                onElegirEnMapa(lat, lng)
            },
            onCancelar = { mostrandoMapa = false }
        )
    }

    // Diálogo del sistema "¿Permitir que Cafetería acceda a la ubicación?"
    val pedirPermiso = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultado ->
        // Basta con "precisa" o "aproximada"
        if (resultado.values.any { it }) onUsarUbicacion() else onPermisoDenegado()
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Entrega en el campus (opcional)", fontWeight = FontWeight.Bold)
            Text(
                "Comparte tu ubicación actual o elige el punto en el mapa y te llevamos el pedido.",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))

            val ubicacion = estado.ubicacion
            when {
                estado.buscandoUbicacion -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Buscando tu ubicación…", fontSize = 14.sp)
                }
                ubicacion != null -> Column {
                    val lugar = ubicacion.direccion
                        ?: if (estado.buscandoDireccion) "Buscando dirección…" else "Dirección no disponible"
                    Text("📍 $lugar", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    val detalle = buildString {
                        append(formatearCoordenadas(ubicacion.latitud, ubicacion.longitud))
                        ubicacion.precisionMetros?.let { append(" · ±${it.toInt()} m") } ?: append(" · elegida en el mapa")
                    }
                    Text(detalle, fontSize = 12.sp, color = Color.Gray)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { mostrandoMapa = true }, enabled = !estado.enviando) { Text("Cambiar en el mapa") }
                        TextButton(onClick = onQuitar, enabled = !estado.enviando) { Text("Quitar") }
                    }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (UbicacionProvider(context).tienePermiso()) {
                                onUsarUbicacion()
                            } else {
                                pedirPermiso.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        },
                        enabled = !estado.enviando,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📍 Usar mi ubicación")
                    }
                    // Alternativa sin permiso: el usuario marca el punto a mano
                    OutlinedButton(
                        onClick = { mostrandoMapa = true },
                        enabled = !estado.enviando,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🗺️ Elegir en el mapa")
                    }
                }
            }

            estado.mensajeUbicacion?.let { mensaje ->
                Text(mensaje, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
            // Si el usuario eligió "No volver a preguntar", Android ya no muestra el diálogo:
            // la única forma de activarlo es desde los Ajustes de la app.
            if (estado.permisoUbicacionDenegado && estado.ubicacion == null) {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Abrir ajustes de la app")
                }
            }
        }
    }
}

/** Al enviar el carrito se genera su comanda y se muestra de inmediato. */
@Composable
fun PedidoConfirmadoDialog(pedido: Order, onCerrar: () -> Unit, onVerPedidos: () -> Unit) {
    ComandaDialog(
        pedido = pedido,
        titulo = "✅ ¡Pedido enviado! Esta es tu comanda",
        onCerrar = onCerrar,
        accionExtra = "🧾 Ver mis pedidos" to onVerPedidos
    )
}

// =============================================
// Consulta: pestaña "Pedidos"
// =============================================

@Composable
fun PedidosScreen(ordersViewModel: OrdersViewModel, onAvisos: (Order) -> Unit = {}) {
    val consulta by ordersViewModel.consulta.collectAsState()
    val foco = LocalFocusManager.current

    // Si ya hay un teléfono guardado, consulta en cuanto se abre la pestaña
    LaunchedEffect(Unit) {
        if (consulta.busqueda.length == 10 && consulta.busqueda.all(Char::isDigit)) ordersViewModel.consultar()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = consulta.busqueda,
                onValueChange = ordersViewModel::onBusquedaChange,
                label = { Text("Teléfono o folio") },
                placeholder = { Text("CMD-7K3F9Q") },
                singleLine = true,
                isError = consulta.errorBusqueda != null,
                supportingText = consulta.errorBusqueda?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = {
                    foco.clearFocus()
                    ordersViewModel.consultar()
                }),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                foco.clearFocus()
                ordersViewModel.consultar()
            }) {
                Text("Consultar")
            }
        }

        when (val estado = consulta.estado) {
            is PedidosUiState.Inicio -> MessageState(
                emoji = "🧾",
                title = "Tus pedidos",
                message = "Escribe el teléfono con el que pediste, o el folio de tu comanda (ej. CMD-7K3F9Q)."
            )

            is PedidosUiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            is PedidosUiState.Empty -> MessageState(
                emoji = "🔍",
                title = "Sin pedidos",
                message = "No hay pedidos registrados con ese teléfono o folio."
            )

            is PedidosUiState.Error -> MessageState(
                emoji = "📡",
                title = "No se pudieron consultar los pedidos",
                message = estado.message,
                actionLabel = "Reintentar",
                onAction = ordersViewModel::consultar
            )

            is PedidosUiState.Success -> {
                Text(
                    text = "${estado.pedidos.size} pedido(s)",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(estado.pedidos, key = { it.id }) { pedido -> PedidoCard(pedido, onAvisos = { onAvisos(pedido) }) }
                }
            }
        }
    }
}

@Composable
fun PedidoCard(pedido: Order, onAvisos: (() -> Unit)? = null) {
    var viendoComanda by remember { mutableStateOf(false) }
    if (viendoComanda) {
        ComandaDialog(pedido = pedido, titulo = "🧾 Comanda", onCerrar = { viendoComanda = false })
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Pedido ${folioComanda(pedido)}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    pedido.status.replaceFirstChar { it.uppercase() },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Text("${pedido.customer} · ${formatearFecha(pedido.createdAt)}", fontSize = 12.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
            pedido.items.forEach { item ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${item.quantity} × ${item.name}", fontSize = 14.sp)
                    Text(formatPrice(item.unitPrice * item.quantity), fontSize = 14.sp)
                }
            }
            if (pedido.notes.isNotBlank()) {
                Text("📝 ${pedido.notes}", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
            }
            if (pedido.latitude != null && pedido.longitude != null) {
                Text(
                    "📍 Entrega en ${pedido.deliveryAddress ?: formatearCoordenadas(pedido.latitude, pedido.longitude)}",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total", fontWeight = FontWeight.Bold)
                Text(formatPrice(pedido.total), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            OutlinedButton(
                onClick = { viendoComanda = true },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("🧾 Ver comanda")
            }
            // Práctica 6: avisos entre este teléfono (cliente) y el barista sobre este pedido
            if (onAvisos != null && pedido.folio.isNotBlank()) {
                OutlinedButton(onClick = onAvisos, modifier = Modifier.fillMaxWidth()) {
                    Text("💬 Avisos con el barista")
                }
            }
        }
    }
}
