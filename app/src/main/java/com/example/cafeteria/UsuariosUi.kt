package com.example.cafeteria

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================
// Práctica 4: usuarios guardados en SQLite con Room
// =============================================

@Composable
fun UsuariosScreen(usuariosViewModel: UsuariosViewModel) {
    val estado by usuariosViewModel.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var porEliminar by remember { mutableStateOf<Usuario?>(null) }
    val scroll = rememberLazyListState()

    // Al tocar ✏️ en un usuario de la lista, sube hasta el formulario
    LaunchedEffect(estado.editando?.id) {
        if (estado.editando != null) scroll.animateScrollToItem(1)
    }

    // Notificación corta después de registrar, actualizar o eliminar
    LaunchedEffect(estado.mensaje) {
        estado.mensaje?.let {
            snackbar.showSnackbar(it)
            usuariosViewModel.mensajeMostrado()
        }
    }

    porEliminar?.let { usuario ->
        AlertDialog(
            onDismissRequest = { porEliminar = null },
            title = { Text("¿Eliminar usuario?") },
            text = { Text("Se borrará a ${usuario.nombre} de la base de datos del teléfono.") },
            confirmButton = {
                TextButton(onClick = {
                    usuariosViewModel.eliminar(usuario)
                    porEliminar = null
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { porEliminar = null }) { Text("Cancelar") } }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = scroll,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item { EncabezadoUsuarios() }
            item { FormularioUsuario(estado, usuariosViewModel) }
            item {
                FilledTonalButton(
                    onClick = usuariosViewModel::verUsuarios,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("👥  VER usuarios", fontWeight = FontWeight.Bold)
                }
            }

            when (val lista = estado.usuarios) {
                null -> item {
                    Text(
                        "Presiona \"VER usuarios\" para consultar la tabla con dao.obtenerTodos().",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    )
                }
                else -> {
                    item {
                        Text(
                            if (lista.isEmpty()) "La tabla usuarios está vacía."
                            else "${lista.size} usuario(s) registrado(s)",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    items(lista, key = { it.id }) { usuario ->
                        UsuarioCard(
                            usuario = usuario,
                            enEdicion = estado.editando?.id == usuario.id,
                            onEditar = { usuariosViewModel.editar(usuario) },
                            onEliminar = { porEliminar = usuario }
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun EncabezadoUsuarios() {
    val colores = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(colores.primary, colores.tertiary)))
            .padding(16.dp)
    ) {
        Text("👤 Usuarios", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colores.onPrimary)
        Text(
            "Guardados en SQLite (Room) dentro del teléfono · funciona sin internet",
            fontSize = 13.sp,
            color = colores.onPrimary.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun FormularioUsuario(estado: UsuariosUiState, vm: UsuariosViewModel) {
    val foco = LocalFocusManager.current
    val editando = estado.editando

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                if (editando == null) "Registrar usuario" else "Modificar usuario #${editando.id}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = estado.nombre,
                onValueChange = vm::onNombreChange,
                label = { Text("Nombre") },
                singleLine = true,
                isError = estado.errorNombre != null,
                supportingText = estado.errorNombre?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = estado.edad,
                onValueChange = vm::onEdadChange,
                label = { Text("Edad") },
                singleLine = true,
                isError = estado.errorEdad != null,
                supportingText = estado.errorEdad?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = estado.profesion,
                onValueChange = vm::onProfesionChange,
                label = { Text("Profesión / carrera") },
                placeholder = { Text("Ej. Ing. en Software") },
                singleLine = true,
                isError = estado.errorProfesion != null,
                supportingText = estado.errorProfesion?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            // Caso 4: campo nuevo en la interfaz
            OutlinedTextField(
                value = estado.correo,
                onValueChange = vm::onCorreoChange,
                label = { Text("Correo") },
                singleLine = true,
                isError = estado.errorCorreo != null,
                supportingText = estado.errorCorreo?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { foco.clearFocus() }),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editando != null) {
                    OutlinedButton(
                        onClick = vm::cancelarEdicion,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("Cancelar") }
                }
                Button(
                    onClick = {
                        foco.clearFocus()
                        vm.guardar()
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(if (editando == null) "Guardar" else "Actualizar", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun UsuarioCard(usuario: Usuario, enEdicion: Boolean, onEditar: () -> Unit, onEliminar: () -> Unit) {
    val colores = MaterialTheme.colorScheme
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = colores.surface),
        border = if (enEdicion) BorderStroke(2.dp, colores.primary) else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Inicial del nombre como avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colores.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    usuario.nombre.firstOrNull()?.uppercase() ?: "?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colores.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(usuario.nombre, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("🎓 ${usuario.profesion} · ${usuario.edad} años", fontSize = 13.sp)
                if (usuario.correo.isNotBlank()) {
                    Text("✉️ ${usuario.correo}", fontSize = 12.sp, color = Color.Gray)
                }
                Text("id ${usuario.id}", fontSize = 11.sp, color = Color.Gray)
            }
            IconButton(onClick = onEditar) { Text("✏️", fontSize = 20.sp) }
            IconButton(onClick = onEliminar) { Text("🗑️", fontSize = 20.sp) }
        }
    }
}
