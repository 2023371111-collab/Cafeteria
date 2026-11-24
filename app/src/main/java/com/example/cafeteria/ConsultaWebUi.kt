package com.example.cafeteria

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================
// Práctica 3: ConsultaWeb, consumo de una URL preasignada
// =============================================

@Composable
fun ConsultaWebScreen(vm: ConsultaWebViewModel) {
    val url by vm.url.collectAsState()
    val estado by vm.estado.collectAsState()
    val foco = LocalFocusManager.current
    val colores = MaterialTheme.colorScheme
    val consultando = estado is ConsultaUiState.Consultando

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // RF01: título
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(colores.primary, colores.tertiary)))
                .padding(16.dp)
        ) {
            Text("🌐 Consulta de información", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colores.onPrimary)
            Text(
                "ConsultaWeb · extensión del sitio de la cafetería",
                fontSize = 13.sp,
                color = colores.onPrimary.copy(alpha = 0.85f)
            )
        }

        // URL preasignada (editable para la prueba 3)
        Card(colors = CardDefaults.cardColors(containerColor = colores.surface), elevation = CardDefaults.cardElevation(2.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("URL preasignada", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                OutlinedTextField(
                    value = url,
                    onValueChange = vm::onUrlChange,
                    singleLine = true,
                    enabled = !consultando,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { foco.clearFocus(); vm.consultar() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                if (url != ConsultaWebViewModel.URL_PREASIGNADA) {
                    TextButton(onClick = vm::restaurarUrl, enabled = !consultando, contentPadding = PaddingValues(0.dp)) {
                        Text("↺ Restaurar URL original")
                    }
                }
            }
        }

        // RF02: botón CONSULTAR
        Button(
            onClick = { foco.clearFocus(); vm.consultar() },
            enabled = !consultando,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("CONSULTAR", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }

        // Área de respuesta
        when (val e = estado) {
            ConsultaUiState.Inicio -> Text(
                "Presiona CONSULTAR para hacer la petición HTTP a la URL.",
                color = Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // RF05: indicador de carga
            ConsultaUiState.Consultando -> Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text("Consultando...", fontSize = 16.sp)
            }

            // RF06: error de conexión
            is ConsultaUiState.Error -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colores.errorContainer)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("📡", fontSize = 40.sp)
                Text(
                    "No fue posible obtener la información.",
                    fontWeight = FontWeight.Bold,
                    color = colores.onErrorContainer
                )
                Text(e.detalle, fontSize = 12.sp, color = colores.onErrorContainer.copy(alpha = 0.8f))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = vm::consultar) { Text("Reintentar") }
            }

            // RF04: información obtenida
            is ConsultaUiState.Exito -> RespuestaCard(e.respuesta)
        }
    }
}

@Composable
private fun RespuestaCard(respuesta: RespuestaWeb) {
    var verCrudo by remember(respuesta) { mutableStateOf(respuesta.campos.isEmpty()) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("✅ Respuesta recibida", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "HTTP ${respuesta.codigo} · ${respuesta.milisegundos} ms · ${respuesta.bytes} bytes",
                fontSize = 12.sp,
                color = Color.Gray
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Respuesta procesada: un renglón por campo del JSON
            respuesta.campos.forEach { (campo, valor) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(campo, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(0.42f))
                    Text(valor, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.58f))
                }
            }

            if (respuesta.campos.isNotEmpty()) {
                TextButton(onClick = { verCrudo = !verCrudo }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (verCrudo) "Ocultar respuesta original" else "Ver respuesta original")
                }
            }
            if (verCrudo) {
                Text(
                    respuesta.cuerpo.take(4000),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                )
            }
        }
    }
}
