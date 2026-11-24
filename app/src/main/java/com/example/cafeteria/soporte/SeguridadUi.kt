package com.example.cafeteria.soporte

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================
// Práctica 10: matriz de pruebas de seguridad ejecutable.
// Las operaciones se mandan directo a Firestore (sin las validaciones de la app):
// lo que se rechaza aquí lo rechazan las REGLAS del servidor.
// =============================================

@Composable
fun SeguridadPanel(estado: SoporteUiState, perfil: Perfil, vm: SoporteViewModel) {
    var idAjena by remember { mutableStateOf("") }
    var idPropia by remember { mutableStateOf("") }
    var uidAjeno by remember { mutableStateOf("") }
    val ocupado = estado.probando

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("SEG-01 y SEG-02 se prueban en la pantalla de inicio de sesión (Logcat: tag SoporteFS). " +
                "Para SEG-04/09 escribe el UID del otro dispositivo (aparece arriba en su pantalla).",
                fontSize = 12.sp, color = Color.Gray)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = idPropia, onValueChange = { idPropia = it.uppercase().take(20) },
                        label = { Text("ID de MI incidencia") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = idAjena, onValueChange = { idAjena = it.uppercase().take(20) },
                        label = { Text("ID de una incidencia AJENA (del otro usuario)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = uidAjeno, onValueChange = { uidAjeno = it.trim().take(40) },
                        label = { Text("UID ajeno (del otro usuario)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Boton("SEG-03 · Crear con mi UID", !ocupado) { vm.probar { listOf(crearPropia(it)) } }
                Boton("SEG-04 · Crear con UID ajeno", !ocupado && uidAjeno.isNotBlank()) { vm.probar { listOf(suplantarPropietario(uidAjeno, it.nombre)) } }
                Boton("SEG-05 · Leer incidencia ajena", !ocupado && idAjena.isNotBlank()) { vm.probar { listOf(leerAjena(idAjena)) } }
                Boton("SEG-06 · Modificar incidencia ajena", !ocupado && idAjena.isNotBlank()) { vm.probar { listOf(modificarAjena(idAjena)) } }
                Boton("SEG-07 · Eliminar incidencia ajena", !ocupado && idAjena.isNotBlank()) { vm.probar { listOf(eliminarAjena(idAjena)) } }
                Boton("SEG-08 · Cambiar estado de mi incidencia", !ocupado && idPropia.isNotBlank()) { vm.probar { listOf(actualizarPropia(idPropia)) } }
                Boton("SEG-09 · Cambiar el propietario de la mía", !ocupado && idPropia.isNotBlank() && uidAjeno.isNotBlank()) { vm.probar { listOf(cambiarPropietario(idPropia, uidAjeno)) } }
                Boton("SEG-10 · Leer y escribir SIN sesión", !ocupado) { vm.probar { sinAutenticacion() } }
                Boton("Extra · Campos inesperados / tipo incorrecto", !ocupado) { vm.probar { listOf(camposInesperados(it)) } }
                Boton("Extra · Subirme el rol a admin", !ocupado) { vm.probar { listOf(escalarRol()) } }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Resultados (${estado.pruebas.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (ocupado) CircularProgressIndicator(Modifier.size(20.dp))
                TextButton(onClick = vm::limpiarPruebas, enabled = estado.pruebas.isNotEmpty()) { Text("Limpiar") }
            }
            Text("Probando como ${perfil.rol.etiqueta}", fontSize = 12.sp, color = Color.Gray)
        }
        items(estado.pruebas) { r ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (r.paso) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("${if (r.paso) "✅ PASA" else "❌ FALLA"} · ${r.clave} · ${r.hora}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(r.accion, fontSize = 13.sp)
                    Text("Esperado: ${r.esperado} · Obtenido: ${if (r.permitida) "Permitida" else "Rechazada"}", fontSize = 12.sp)
                    Text(r.detalle, fontSize = 12.sp, color = Color.DarkGray)
                }
            }
        }
    }
}

@Composable
private fun Boton(texto: String, habilitado: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = habilitado, modifier = Modifier.fillMaxWidth()) { Text(texto) }
}
