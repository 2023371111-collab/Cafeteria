package com.example.cafeteria

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// =============================================
// Comanda: el ticket del pedido que se generó con el carrito.
// Se arma con lo que regresa el servidor (precios y total ya validados),
// no con lo que había en el teléfono.
// =============================================

/** Folio con el que se identifica la comanda (si el servidor aún no manda folio, el número de pedido). */
fun folioComanda(pedido: Order): String = pedido.folio.ifBlank { "#${pedido.id}" }

/** "Para recoger" o la dirección de entrega. */
private fun tipoEntrega(pedido: Order): String =
    if (pedido.latitude != null && pedido.longitude != null) {
        "Entrega: ${pedido.deliveryAddress ?: formatearCoordenadas(pedido.latitude, pedido.longitude)}"
    } else {
        "Para recoger en la cafetería"
    }

/** La comanda en texto plano, para compartirla por WhatsApp, correo, etc. */
fun textoComanda(pedido: Order): String = buildString {
    appendLine("☕ CAFETERÍA MÓVIL")
    appendLine("COMANDA ${folioComanda(pedido)}")
    appendLine(formatearFecha(pedido.createdAt))
    appendLine("Cliente: ${pedido.customer} (${pedido.phone})")
    appendLine(tipoEntrega(pedido))
    appendLine("------------------------------")
    pedido.items.forEach { item ->
        appendLine("${item.quantity} × ${item.name}  ${formatPrice(item.unitPrice * item.quantity)}")
    }
    appendLine("------------------------------")
    if (pedido.notes.isNotBlank()) appendLine("Notas: ${pedido.notes}")
    appendLine("TOTAL: ${formatPrice(pedido.total)}")
    append("Estado: ${pedido.status.replaceFirstChar { it.uppercase() }}")
}

/**
 * Comanda a pantalla completa. [accionExtra] agrega un botón opcional
 * (por ejemplo "Ver mis pedidos" justo después de enviar el carrito).
 */
@Composable
fun ComandaDialog(
    pedido: Order,
    titulo: String,
    onCerrar: () -> Unit,
    accionExtra: Pair<String, () -> Unit>? = null
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onCerrar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(titulo, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))

                ComandaTicket(pedido)

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val envio = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Comanda ${folioComanda(pedido)}")
                            putExtra(Intent.EXTRA_TEXT, textoComanda(pedido))
                        }
                        context.startActivity(Intent.createChooser(envio, "Compartir comanda"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📤 Compartir comanda")
                }
                accionExtra?.let { (etiqueta, accion) ->
                    OutlinedButton(onClick = accion, modifier = Modifier.fillMaxWidth()) { Text(etiqueta) }
                }
                TextButton(onClick = onCerrar, modifier = Modifier.fillMaxWidth()) { Text("Cerrar") }
            }
        }
    }
}

/** El ticket en sí, con letra monoespaciada como las comandas impresas. */
@Composable
fun ComandaTicket(pedido: Order) {
    val mono = FontFamily.Monospace

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("☕ CAFETERÍA MÓVIL", fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Universidad Tecnológica de Querétaro", fontFamily = mono, fontSize = 11.sp, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            Text("COMANDA", fontFamily = mono, fontSize = 13.sp, letterSpacing = 4.sp)
            Text(folioComanda(pedido), fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 28.sp)
            Text(formatearFecha(pedido.createdAt), fontFamily = mono, fontSize = 12.sp)

            LineaPunteada()
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Cliente:  ${pedido.customer}", fontFamily = mono, fontSize = 13.sp)
                Text("Teléfono: ${pedido.phone}", fontFamily = mono, fontSize = 13.sp)
                Text(tipoEntrega(pedido), fontFamily = mono, fontSize = 13.sp)
            }

            LineaPunteada()
            RenglonComanda("CANT", "PRODUCTO", "IMPORTE", negrita = true)
            pedido.items.forEach { item ->
                RenglonComanda("${item.quantity}", item.name, formatPrice(item.unitPrice * item.quantity))
                Text(
                    "      ${formatPrice(item.unitPrice)} c/u",
                    fontFamily = mono,
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (pedido.notes.isNotBlank()) {
                LineaPunteada()
                Text("📝 ${pedido.notes}", fontFamily = mono, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            }

            LineaPunteada()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("TOTAL", fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(formatPrice(pedido.total), fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Text(
                "${pedido.items.sumOf { it.quantity }} pieza(s) · Estado: ${pedido.status.replaceFirstChar { it.uppercase() }}",
                fontFamily = mono,
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("¡Gracias por tu compra!", fontFamily = mono, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RenglonComanda(cantidad: String, producto: String, importe: String, negrita: Boolean = false) {
    val peso = if (negrita) FontWeight.Bold else FontWeight.Normal
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(cantidad, fontFamily = FontFamily.Monospace, fontWeight = peso, fontSize = 13.sp, modifier = Modifier.width(48.dp))
        Text(producto, fontFamily = FontFamily.Monospace, fontWeight = peso, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(importe, fontFamily = FontFamily.Monospace, fontWeight = peso, fontSize = 13.sp, textAlign = TextAlign.End)
    }
}

@Composable
private fun LineaPunteada() {
    Text(
        "- ".repeat(40),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = Color.Gray,
        maxLines = 1,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}
