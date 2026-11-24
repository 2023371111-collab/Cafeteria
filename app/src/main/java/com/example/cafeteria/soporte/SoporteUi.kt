package com.example.cafeteria.soporte

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.cafeteria.MessageState

// =============================================
// Pestaña "Soporte": reportes de fallas y atención (prácticas 7, 9 y 10)
// =============================================

@Composable
fun SoporteScreen(vm: SoporteViewModel) {
    val estado by vm.estado.collectAsState()
    val detalle by vm.detalle.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(estado.mensaje) {
        estado.mensaje?.let {
            snackbar.showSnackbar(it)
            vm.mensajeMostrado()
        }
    }

    Box(Modifier.fillMaxSize()) {
        val perfil = estado.perfil
        when {
            estado.iniciando -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            perfil == null -> LoginSoporte(estado.login, vm)
            else -> PanelSoporte(estado, perfil, vm)
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (estado.perfil != null) {
        estado.invitacionPendiente?.let { codigo ->
            AlertDialog(
                onDismissRequest = vm::descartarInvitacion,
                title = { Text("🔗 Invitación de rol") },
                text = { Text("Recibiste la invitación $codigo. Al aceptarla tu cuenta toma el rol que el administrador asignó.") },
                confirmButton = { TextButton(onClick = vm::aceptarInvitacion) { Text("Aceptar") } },
                dismissButton = { TextButton(onClick = vm::descartarInvitacion) { Text("Ignorar") } }
            )
        }
        if (estado.formulario.abierto) FormularioDialog(estado.formulario, vm, snackbar)
        if (detalle.id != null) DetalleDialog(vm, estado, detalle, snackbar)
    }
}

// ---------------------------------------------
// Login con Firebase Authentication (correo y contraseña)
// ---------------------------------------------

@Composable
private fun LoginSoporte(login: LoginSoporteState, vm: SoporteViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🛠️", fontSize = 48.sp)
        Text("Soporte Cafetería", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Reportes de fallas compartidos en la nube (Firebase). Usa tu cuenta de Firebase, distinta a la del menú.",
            fontSize = 13.sp, color = Color.Gray
        )
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (login.modoRegistro) "Crear cuenta" else "Iniciar sesión", fontWeight = FontWeight.Bold)
                if (login.modoRegistro) {
                    OutlinedTextField(
                        value = login.nombre, onValueChange = { v -> vm.onLogin { copy(nombre = v.take(40)) } },
                        label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = login.correo, onValueChange = { v -> vm.onLogin { copy(correo = v.take(80)) } },
                    label = { Text("Correo") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = login.contrasena, onValueChange = { v -> vm.onLogin { copy(contrasena = v.take(64)) } },
                    label = { Text("Contraseña") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                login.error?.let {
                    Text("⚠️ $it", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer).padding(8.dp))
                }
                Button(onClick = vm::entrar, enabled = !login.verificando, modifier = Modifier.fillMaxWidth()) {
                    Text(if (login.verificando) "Verificando…" else if (login.modoRegistro) "Crear cuenta" else "Iniciar sesión")
                }
                TextButton(onClick = vm::cambiarModoLogin, modifier = Modifier.fillMaxWidth()) {
                    Text(if (login.modoRegistro) "¿Ya tienes cuenta? Inicia sesión" else "¿No tienes cuenta? Crea una")
                }
            }
        }
    }
}

// ---------------------------------------------
// Panel con sesión
// ---------------------------------------------

@Composable
private fun PanelSoporte(estado: SoporteUiState, perfil: Perfil, vm: SoporteViewModel) {
    var seccion by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        EncabezadoCuenta(perfil, vm)
        TabRow(selectedTabIndex = seccion) {
            Tab(selected = seccion == 0, onClick = { seccion = 0 }, text = { Text("📋 Reportes") })
            Tab(selected = seccion == 1, onClick = { seccion = 1 }, text = { Text("👥 Roles") })
            Tab(selected = seccion == 2, onClick = { seccion = 2 }, text = { Text("🔐 Seguridad") })
        }
        when (seccion) {
            0 -> ListaIncidencias(estado, perfil, vm)
            1 -> RolesPanel(estado, perfil, vm)
            else -> SeguridadPanel(estado, perfil, vm)
        }
    }
}

/** Quién está autenticado en ESTE dispositivo: el UID se muestra para comparar A y B (práctica 10). */
@Composable
private fun EncabezadoCuenta(perfil: Perfil, vm: SoporteViewModel) {
    val portapapeles = LocalClipboardManager.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${perfil.rol.icono} ${perfil.nombre} · ${perfil.rol.etiqueta}", fontWeight = FontWeight.Bold)
                    Text("${perfil.correo} · 📱 ${vm.repo.dispositivo}", fontSize = 12.sp)
                }
                TextButton(onClick = vm::cerrarSesion) { Text("Salir") }
            }
            SelectionContainer {
                Text("UID: ${perfil.uid}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Proyecto Firebase: ${vm.repo.proyecto}", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                TextButton(onClick = { portapapeles.setText(AnnotatedString(perfil.uid)) }, contentPadding = PaddingValues(4.dp)) {
                    Text("Copiar UID", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ListaIncidencias(estado: SoporteUiState, perfil: Perfil, vm: SoporteViewModel) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = estado.busqueda, onValueChange = vm::onBusqueda,
                    label = { Text("Consultar por ID") }, placeholder = { Text("REP001") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = vm::buscarPorId, enabled = estado.busqueda.isNotBlank()) { Text("Buscar") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (estado.tiempoReal) "🟢 Tiempo real" else "🔄 Consulta manual") +
                        " · ${estado.incidencias.size} activo(s) · ${SoporteViewModel.hora(estado.ultimaActualizacion)}" +
                        if (estado.desdeCache) " · 📴 desde caché" else "",
                    fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = vm::actualizarLista, enabled = !estado.cargandoLista) { Text("↻ Actualizar") }
            }
            val error = estado.errorLista
            when {
                estado.cargandoLista && estado.incidencias.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null && estado.incidencias.isEmpty() ->
                    MessageState("📡", "No se pudieron cargar los reportes", error, "Reintentar", vm::actualizarLista)
                estado.incidencias.isEmpty() ->
                    MessageState("📋", "Sin reportes activos", "Toca “＋ Nuevo reporte” para registrar una falla.")
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(estado.incidencias, key = { it.id }) { inc ->
                        IncidenciaCard(inc, estado.cuentas, perfil, onAbrir = { vm.abrirDetalle(inc.id) })
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = vm::abrirFormulario,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Text("＋ Nuevo reporte") }
    }
}

@Composable
fun Etiqueta(texto: String, color: Color = MaterialTheme.colorScheme.primaryContainer) {
    Text(
        texto, fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

fun colorPrioridad(prioridad: String) = when (prioridad) {
    "Crítica" -> Color(0xFFFFCDD2)
    "Alta" -> Color(0xFFFFE0B2)
    "Media" -> Color(0xFFFFF9C4)
    else -> Color(0xFFE0F2F1)
}

@Composable
private fun IncidenciaCard(inc: Incidencia, cuentas: List<Perfil>, perfil: Perfil, onAbrir: () -> Unit) {
    val agente = inc.asignadoA?.let { uid -> cuentas.firstOrNull { it.uid == uid } }
    Card(onClick = onAbrir, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(inc.id, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Etiqueta(inc.estado)
                Spacer(Modifier.width(4.dp))
                Etiqueta(inc.prioridad, colorPrioridad(inc.prioridad))
            }
            Text(inc.titulo, fontWeight = FontWeight.SemiBold)
            Text("${inc.tipo} · ${inc.equipo}", fontSize = 12.sp, color = Color.Gray)
            Text(
                "👤 ${inc.creadoPorNombre} · " + when {
                    inc.dirigidoA == Catalogos.PARA_ADMIN -> "🛡️ Solicitud directa al admin"
                    inc.asignadoNombre != null -> "🎧 ${inc.asignadoNombre}"
                    else -> "🎧 Sin asignar"
                } + " · v${inc.version}",
                fontSize = 12.sp
            )
            // Cambio de rol por desconexión: el admin ve que el agente se fue y puede reasignar
            if (perfil.rol == Rol.ADMIN && agente != null && !agente.enLinea()) {
                Text("⚠️ El agente está desconectado: reasigna el reporte", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            if (inc.pendienteDeSincronizar) {
                Text("⏳ Pendiente de sincronizar (guardado solo en este teléfono)", fontSize = 12.sp, color = Color(0xFFE65100))
            }
        }
    }
}

// ---------------------------------------------
// Formulario de alta
// ---------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Selector(etiqueta: String, valor: String, opciones: List<String>, onElegir: (String) -> Unit, modifier: Modifier = Modifier, mostrar: (String) -> String = { it }) {
    var abierto by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = abierto, onExpandedChange = { abierto = it }, modifier = modifier) {
        OutlinedTextField(
            value = mostrar(valor), onValueChange = {}, readOnly = true, label = { Text(etiqueta) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(abierto) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            opciones.forEach { op ->
                DropdownMenuItem(text = { Text(mostrar(op)) }, onClick = { onElegir(op); abierto = false })
            }
        }
    }
}

@Composable
private fun FormularioDialog(f: FormularioIncidencia, vm: SoporteViewModel, snackbar: SnackbarHostState) {
    Dialog(onDismissRequest = vm::cerrarFormulario, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📝 Nuevo reporte", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = f.id, onValueChange = { v -> vm.onFormulario { copy(id = v.uppercase().take(20)) } },
                    label = { Text("ID del reporte") }, singleLine = true, isError = f.errores.id != null,
                    supportingText = { Text(f.errores.id ?: "Único. Si ya existe NO se sobrescribe.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = f.titulo, onValueChange = { v -> vm.onFormulario { copy(titulo = v.take(60)) } },
                    label = { Text("Título") }, singleLine = true, isError = f.errores.titulo != null,
                    supportingText = f.errores.titulo?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth()
                )
                Selector("Tipo de caso", f.tipo, Catalogos.TIPOS, { v -> vm.onFormulario { copy(tipo = v) } }, Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = f.equipo, onValueChange = { v -> vm.onFormulario { copy(equipo = v.take(40)) } },
                    label = { Text("Equipo o lugar") }, placeholder = { Text("Terminal de caja 2") }, singleLine = true,
                    isError = f.errores.equipo != null, supportingText = f.errores.equipo?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = f.descripcion, onValueChange = { v -> vm.onFormulario { copy(descripcion = v.take(300)) } },
                    label = { Text("Descripción") }, minLines = 3, isError = f.errores.descripcion != null,
                    supportingText = { Text(f.errores.descripcion ?: "${f.descripcion.trim().length}/300") },
                    modifier = Modifier.fillMaxWidth()
                )
                Selector("Prioridad", f.prioridad, Catalogos.PRIORIDADES, { v -> vm.onFormulario { copy(prioridad = v) } }, Modifier.fillMaxWidth())
                Selector(
                    "Enviar a", f.dirigidoA, listOf(Catalogos.PARA_SOPORTE, Catalogos.PARA_ADMIN),
                    { v -> vm.onFormulario { copy(dirigidoA = v) } }, Modifier.fillMaxWidth(),
                    mostrar = { if (it == Catalogos.PARA_ADMIN) "🛡️ Directo al administrador" else "🎧 Mesa de ayuda (agentes)" }
                )
                Button(onClick = vm::guardarFormulario, enabled = !f.guardando, modifier = Modifier.fillMaxWidth()) {
                    Text(if (f.guardando) "Guardando en Firestore…" else "Guardar reporte")
                }
                TextButton(onClick = vm::cerrarFormulario, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
            }
            // El aviso (ej. "Ese ID ya existe") se ve encima del formulario
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        } }
    }
}

// ---------------------------------------------
// Roles (práctica 7)
// ---------------------------------------------

@Composable
private fun RolesPanel(estado: SoporteUiState, perfil: Perfil, vm: SoporteViewModel) {
    val context = LocalContext.current
    var codigo by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🔗 Tengo una invitación", fontWeight = FontWeight.Bold)
                    Text("El administrador comparte una URL preasignada (cafeteria://rol?codigo=…). Al abrirla en este teléfono se toma el rol. También puedes escribir el código.", fontSize = 12.sp, color = Color.Gray)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = codigo, onValueChange = { codigo = it.uppercase().take(12) }, label = { Text("Código") }, singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { vm.recibirInvitacion(codigo); codigo = "" }, enabled = codigo.length >= 6) { Text("Usar") }
                    }
                }
            }
        }
        if (perfil.rol == Rol.ADMIN) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🛡️ Generar URL preasignada de rol", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.crearInvitacion(Rol.AGENTE) }) { Text("🎧 Agente") }
                            OutlinedButton(onClick = { vm.crearInvitacion(Rol.USUARIO) }) { Text("👤 Usuario") }
                            OutlinedButton(onClick = { vm.crearInvitacion(Rol.ADMIN) }) { Text("🛡️ Admin") }
                        }
                        estado.ultimaInvitacion?.let { url ->
                            SelectionContainer { Text(url, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                            Button(onClick = {
                                val envio = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }
                                context.startActivity(Intent.createChooser(envio, "Compartir invitación"))
                            }) { Text("📤 Compartir") }
                        }
                    }
                }
            }
        }
        if (perfil.rol.esPersonal) {
            item { Text("Cuentas y dispositivos (${estado.cuentas.size})", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(estado.cuentas, key = { it.uid }) { cuenta ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${if (cuenta.enLinea()) "🟢" else "⚪"} ${cuenta.nombre}" + if (cuenta.uid == perfil.uid) " (tú)" else "", fontWeight = FontWeight.SemiBold)
                            Text("${cuenta.rol.icono} ${cuenta.rol.etiqueta} · 📱 ${cuenta.dispositivo.ifBlank { "—" }}", fontSize = 12.sp)
                            Text("Última conexión: ${SoporteViewModel.hora(cuenta.ultimaConexion)}", fontSize = 11.sp, color = Color.Gray)
                        }
                        if (perfil.rol == Rol.ADMIN && cuenta.uid != perfil.uid) {
                            Selector("Rol", cuenta.rol.valor, Rol.entries.map { it.valor }, { v -> vm.cambiarRol(cuenta, Rol.de(v)) },
                                Modifier.width(150.dp), mostrar = { Rol.de(it).etiqueta })
                        }
                    }
                }
            }
        } else {
            item {
                Text("Como ${perfil.rol.etiqueta} solo ves tus propios reportes. El administrador puede cambiar tu rol o enviarte una invitación.",
                    fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(8.dp))
            }
        }
    }
}
