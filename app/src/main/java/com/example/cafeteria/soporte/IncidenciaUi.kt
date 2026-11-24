package com.example.cafeteria.soporte

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.cafeteria.MessageState
import java.text.SimpleDateFormat
import java.util.Locale

// =============================================
// Detalle de un reporte: datos, edición con control de concurrencia (práctica 9)
// y conversación entre dispositivos con archivos (práctica 7).
// =============================================

private fun fechaHora(d: java.util.Date?) = d?.let { SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(it) } ?: "—"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalleDialog(vm: SoporteViewModel, estado: SoporteUiState, d: DetalleState, snackbar: SnackbarHostState) {
    val perfil = estado.perfil ?: return
    val context = LocalContext.current
    val archivo by vm.archivo.collectAsState()
    var confirmarEliminar by remember { mutableStateOf(false) }
    var confirmarLimpiar by remember { mutableStateOf(false) }
    val elegirArchivo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(vm::adjuntar) }

    // Abrir un adjunto descargado con la app que corresponda (visor de imágenes, PDF...)
    LaunchedEffect(archivo) {
        archivo?.let {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it.uri, it.tipo).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: ActivityNotFoundException) {
                snackbar.showSnackbar("No hay una app para abrir este archivo.")
            }
            vm.archivoAbierto()
        }
    }

    if (confirmarEliminar) {
        AlertDialog(
            onDismissRequest = { confirmarEliminar = false },
            title = { Text("¿Eliminar ${d.id}?") },
            text = { Text("Dejará de aparecer como registro activo. El documento queda en Firestore como historial (activo = false).") },
            confirmButton = { TextButton(onClick = { confirmarEliminar = false; vm.eliminar() }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmarEliminar = false }) { Text("Cancelar") } }
        )
    }
    if (confirmarLimpiar) {
        AlertDialog(
            onDismissRequest = { confirmarLimpiar = false },
            title = { Text("¿Limpiar historial?") },
            text = { Text("Se ocultan los mensajes en este dispositivo. En Firestore se conservan.") },
            confirmButton = { TextButton(onClick = { confirmarLimpiar = false; vm.limpiarHistorial() }) { Text("Limpiar") } },
            dismissButton = { TextButton(onClick = { confirmarLimpiar = false }) { Text("Cancelar") } }
        )
    }
    d.conflicto?.let { c -> ConflictoDialog(c, vm) }

    Dialog(onDismissRequest = vm::cerrarDetalle, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text("Reporte ${d.id}") },
                    navigationIcon = { TextButton(onClick = vm::cerrarDetalle) { Text("← Volver") } }
                )
            }
        ) { padding ->
            val inc = d.incidencia
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    d.noEncontrada -> MessageState("🔍", "No existe el reporte ${d.id}", "No hay ningún documento con ese ID en Firestore (o fue eliminado).")
                    inc == null && d.error != null -> MessageState("🔒", "No se puede consultar ${d.id}", d.error)
                    inc == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> ContenidoDetalle(vm, estado, perfil, d, inc,
                        onEliminar = { confirmarEliminar = true },
                        onLimpiar = { confirmarLimpiar = true },
                        onAdjuntar = { elegirArchivo.launch("*/*") })
                }
            }
        }
    }
}

@Composable
private fun ContenidoDetalle(
    vm: SoporteViewModel, estado: SoporteUiState, perfil: Perfil, d: DetalleState, inc: Incidencia,
    onEliminar: () -> Unit, onLimpiar: () -> Unit, onAdjuntar: () -> Unit
) {
    val esDueno = inc.creadoPor == perfil.uid
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // ----- Datos -----
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(inc.titulo, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            Etiqueta(inc.estado); Spacer(Modifier.width(4.dp)); Etiqueta(inc.prioridad, colorPrioridad(inc.prioridad))
                        }
                        Text("${inc.tipo} · ${inc.equipo}", fontSize = 13.sp)
                        Text(inc.descripcion, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
                        Text("Creado por ${inc.creadoPorNombre} · ${fechaHora(inc.creadoEn)}", fontSize = 12.sp, color = Color.Gray)
                        Text("Versión ${inc.version} · última modificación ${fechaHora(inc.actualizadoEn)} por ${inc.actualizadoPorNombre ?: "—"}", fontSize = 12.sp, color = Color.Gray)
                        Text("creadoPor (UID): ${inc.creadoPor}", fontSize = 11.sp, color = Color.Gray)
                        d.ultimoCambioRemoto?.let { Text("🔄 Cambio de otro dispositivo: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                        if (inc.pendienteDeSincronizar) Text("⏳ Pendiente de sincronizar con el servidor", fontSize = 12.sp, color = Color(0xFFE65100))
                    }
                }
            }
            // ----- Edición -----
            item { Edicion(vm, d, inc) }
            // ----- Asignación y acciones -----
            item { Acciones(vm, estado, perfil, inc, esDueno, onEliminar) }
            // ----- Conversación -----
            item { EncabezadoConversacion(d, inc, perfil, vm, onLimpiar) }
            if (d.visibles.isEmpty()) {
                item { Text(if (d.ocultosHasta > 0) "Historial limpio." else "Sin mensajes todavía.", color = Color.Gray, fontSize = 13.sp) }
            }
            items(d.visibles, key = { it.id }) { m -> Burbuja(m, propio = m.emisorUid == perfil.uid, onAbrir = { vm.abrirAdjunto(m) }) }
        }
        Redactor(vm, d, perfil, onAdjuntar)
    }
}

@Composable
private fun Edicion(vm: SoporteViewModel, d: DetalleState, inc: Incidencia) {
    val original = d.original ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("✏️ Editar (se guarda al presionar Guardar)", fontWeight = FontWeight.Bold)
            if (inc.version != original.version) {
                Text(
                    "⚠️ Otro dispositivo guardó la versión ${inc.version} mientras editabas (abriste la ${original.version}). " +
                        "Al guardar se combinan los campos distintos; si cambió el mismo campo verás un aviso.",
                    fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFF3E0)).padding(8.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Selector("Estado", d.estado, Catalogos.ESTADOS, { v -> vm.onEdicion { copy(estado = v) } }, Modifier.weight(1f))
                Selector("Prioridad", d.prioridad, Catalogos.PRIORIDADES, { v -> vm.onEdicion { copy(prioridad = v) } }, Modifier.weight(1f))
            }
            OutlinedTextField(
                value = d.descripcion, onValueChange = { v -> vm.onEdicion { copy(descripcion = v.take(300)) } },
                label = { Text("Descripción") }, minLines = 2, modifier = Modifier.fillMaxWidth()
            )
            val n = d.cambios.size
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.guardarEdicion() }, enabled = n > 0 && !d.guardando) {
                    Text(if (d.guardando) "Guardando…" else "💾 Guardar ($n)")
                }
                OutlinedButton(onClick = vm::recargarEdicion, enabled = !d.guardando) { Text("↺ Descartar / recargar") }
            }
            if (d.ofrecerSinConexion) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(8.dp)
                ) {
                    Text("📡 Sin conexión: el cambio NO se guardó en el servidor.", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Puedes dejarlo guardado en este teléfono; Firestore lo enviará al volver la red y la lista lo marcará como pendiente.", fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.guardarEdicion() }) { Text("Reintentar") }
                        TextButton(onClick = vm::guardarSinConexion) { Text("Guardar sin conexión") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictoDialog(c: ResultadoGuardado.Conflicto, vm: SoporteViewModel) {
    AlertDialog(
        onDismissRequest = vm::recargarEdicion,
        title = { Text("⚠️ Conflicto de edición") },
        text = {
            Column {
                Text("Otro dispositivo cambió los mismos campos (versión ${c.versionServidor}):")
                c.campos.forEach { (campo, valores) ->
                    Text("• $campo: abriste “${valores.first}” y ahora es “${valores.second}”", fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.guardarEdicion(forzar = true) }) { Text("Sobrescribir con lo mío") } },
        dismissButton = { TextButton(onClick = vm::recargarEdicion) { Text("Quedarme con lo del servidor") } }
    )
}

@Composable
private fun Acciones(vm: SoporteViewModel, estado: SoporteUiState, perfil: Perfil, inc: Incidencia, esDueno: Boolean, onEliminar: () -> Unit) {
    val agentes = estado.cuentas.filter { it.rol == Rol.AGENTE }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (perfil.rol) {
                Rol.ADMIN -> {
                    Text("🛡️ Asignar a un agente", fontWeight = FontWeight.Bold)
                    if (agentes.isEmpty()) Text("No hay cuentas con rol agente.", fontSize = 12.sp, color = Color.Gray)
                    agentes.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${if (a.enLinea()) "🟢" else "⚪"} ${a.nombre} · ${a.dispositivo}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                            if (inc.asignadoA == a.uid) Text("Asignado", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            else TextButton(onClick = { vm.asignar(a) }) { Text("Asignar") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.asignar(perfil) }) { Text("Atender yo") }
                        if (inc.asignadoA != null) OutlinedButton(onClick = { vm.asignar(null) }) { Text("Quitar asignación") }
                    }
                }
                Rol.AGENTE -> {
                    Text("🎧 Atención", fontWeight = FontWeight.Bold)
                    if (inc.asignadoA == perfil.uid) Text("Este reporte está asignado a ti.", fontSize = 13.sp)
                    else Button(onClick = { vm.asignar(perfil) }) { Text("Tomar este reporte") }
                }
                Rol.USUARIO -> Text(
                    if (inc.dirigidoA == Catalogos.PARA_ADMIN) "🛡️ Solicitud directa al administrador"
                    else "🎧 Atiende: ${inc.asignadoNombre ?: "pendiente de asignar"}",
                    fontSize = 13.sp
                )
            }
            if (esDueno || perfil.rol == Rol.ADMIN) {
                TextButton(onClick = onEliminar) { Text("🗑️ Eliminar reporte", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** Los dos dispositivos conectados en la conversación y el contador de mensajes de cada uno. */
@Composable
private fun EncabezadoConversacion(d: DetalleState, inc: Incidencia, perfil: Perfil, vm: SoporteViewModel, onLimpiar: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💬 Conversación", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onLimpiar, enabled = d.visibles.isNotEmpty()) { Text("🧹 Limpiar historial") }
            }
            fun linea(etiqueta: String, p: Perfil?, respaldo: String) =
                if (p == null) "$etiqueta: $respaldo"
                else "$etiqueta: ${if (p.enLinea()) "🟢" else "⚪"} ${p.nombre} (${p.rol.etiqueta}) · 📱 ${p.dispositivo.ifBlank { "—" }}"
            Text(linea("Dispositivo 1", d.creador, inc.creadoPorNombre), fontSize = 13.sp)
            Text(linea("Dispositivo 2", d.asignado,
                if (inc.dirigidoA == Catalogos.PARA_ADMIN) "🛡️ Administrador" else "🎧 Mesa de ayuda (sin asignar)"), fontSize = 13.sp)
            val porDispositivo = d.visibles.groupingBy { "${it.emisorNombre} (${it.emisorDispositivo})" }.eachCount()
            Text(
                "Enviados: " + if (porDispositivo.isEmpty()) "0" else porDispositivo.entries.joinToString(" · ") { "${it.key}: ${it.value}" },
                fontSize = 12.sp
            )
            d.error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun Burbuja(m: MensajeSoporte, propio: Boolean, onAbrir: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (propio) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp))
                .background(if (propio) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("${m.emisorRol.icono} ${m.emisorNombre} · ${m.emisorDispositivo}" + if (m.para != "todos") " → ${m.para}" else "",
                fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (m.texto.isNotBlank()) Text(m.texto, fontSize = 15.sp)
            if (m.archivoId != null) {
                AssistChip(onClick = onAbrir, label = { Text("📎 ${m.archivoNombre} (${m.archivoBytes / 1024} KB) · Abrir", fontSize = 12.sp) })
            }
            Text(
                SoporteViewModel.hora(m.enviadoEn) + if (m.pendiente) " · ⏳ enviando" else " · ✓",
                fontSize = 11.sp, color = Color.Gray, modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
private fun Redactor(vm: SoporteViewModel, d: DetalleState, perfil: Perfil, onAdjuntar: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            // El administrador elige a qué dispositivo dirige el mensaje
            val destinos = when (perfil.rol) {
                Rol.ADMIN -> listOf("todos", "agente", "usuario")
                Rol.AGENTE -> listOf("todos", "admin", "usuario")
                Rol.USUARIO -> listOf("todos", "admin", "agente")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Emisor: ${perfil.rol.icono} ${perfil.rol.etiqueta}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Selector("Para", d.para, destinos, vm::onPara, Modifier.width(150.dp))
            }
            d.adjunto?.let { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📎 ${a.nombre} · ${a.datos.size / 1024} KB", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::quitarAdjunto) { Text("Quitar") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAdjuntar) { Text("📎", fontSize = 20.sp) }
                OutlinedTextField(
                    value = d.texto, onValueChange = vm::onTexto,
                    placeholder = { Text("Escribe un mensaje…") }, maxLines = 3,
                    supportingText = { Text("${d.texto.trim().length}/${DetalleState.MAX_TEXTO}") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Button(onClick = vm::enviarMensaje, enabled = d.puedeEnviar) { Text(if (d.enviando) "…" else "Enviar") }
            }
        }
    }
}
