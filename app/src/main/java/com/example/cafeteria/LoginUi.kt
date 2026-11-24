package com.example.cafeteria

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================
// Práctica 5: AccesoSeguro, iniciar sesión y crear cuenta
// =============================================

@Composable
fun LoginScreen(sesionViewModel: SesionViewModel) {
    val estado by sesionViewModel.login.collectAsState()
    val foco = LocalFocusManager.current
    val colores = MaterialTheme.colorScheme
    var verContrasena by remember { mutableStateOf(false) }
    val registro = estado.modoRegistro
    val errores = estado.erroresRegistro
    val bloqueado = estado.verificando || (!registro && estado.esperaSegundos > 0)
    val usuarioExcedido = estado.usuario.trim().length > ValidadorAcceso.MAX_USUARIO

    val enviar = {
        foco.clearFocus()
        if (registro) sesionViewModel.crearCuenta() else sesionViewModel.iniciarSesion()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colores.primary, colores.tertiary)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(88.dp).clip(CircleShape).background(colores.onPrimary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Text("☕", fontSize = 44.sp) }
            Spacer(modifier = Modifier.height(12.dp))
            Text("Cafetería Móvil", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = colores.onPrimary)
            Text(
                if (registro) "Crea tu cuenta para empezar" else "Inicia sesión para continuar",
                fontSize = 14.sp,
                color = colores.onPrimary.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(24.dp))

            Card(
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = colores.surface)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(
                        if (registro) "Crear cuenta" else "Iniciar sesión",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Solo al crear cuenta: nombre para mostrar
                    AnimatedVisibility(visible = registro) {
                        OutlinedTextField(
                            value = estado.nombre,
                            onValueChange = sesionViewModel::onNombreChange,
                            label = { Text("Nombre") },
                            leadingIcon = { Text("🪪", fontSize = 18.sp) },
                            singleLine = true,
                            enabled = !bloqueado,
                            isError = errores.nombre != null,
                            supportingText = { Text(errores.nombre ?: "Como quieres que te saludemos") },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 1. Campo de usuario
                    OutlinedTextField(
                        value = estado.usuario,
                        onValueChange = sesionViewModel::onUsuarioChange,
                        label = { Text("Usuario") },
                        leadingIcon = { IconoCampo(R.drawable.ic_usuario) },
                        singleLine = true,
                        enabled = !bloqueado,
                        isError = usuarioExcedido || errores.usuario != null,
                        // Contador para que se vea el límite de longitud
                        supportingText = {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    errores.usuario ?: if (registro) "Letras, números, punto, guion y guion bajo" else "",
                                    modifier = Modifier.weight(1f)
                                )
                                Text("${estado.usuario.trim().length}/${ValidadorAcceso.MAX_USUARIO}")
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 1 y 4. Campo de contraseña con los caracteres ocultos (••••)
                    OutlinedTextField(
                        value = estado.contrasena,
                        onValueChange = sesionViewModel::onContrasenaChange,
                        label = { Text("Contraseña") },
                        leadingIcon = { IconoCampo(R.drawable.ic_candado) },
                        trailingIcon = {
                            // Ojo abierto = tocar para mostrar; ojo cerrado = tocar para ocultar
                            IconButton(onClick = { verContrasena = !verContrasena }) {
                                Icon(
                                    painterResource(if (verContrasena) R.drawable.ic_ojo_cerrado else R.drawable.ic_ojo_abierto),
                                    contentDescription = if (verContrasena) "Ocultar contraseña" else "Mostrar contraseña",
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        },
                        singleLine = true,
                        enabled = !bloqueado,
                        isError = errores.contrasena != null,
                        visualTransformation = if (verContrasena) VisualTransformation.None else PasswordVisualTransformation(),
                        supportingText = {
                            Text(
                                errores.contrasena
                                    ?: if (registro) "Mínimo ${ValidadorAcceso.MIN_CONTRASENA} caracteres, con letras y números"
                                    else "Mínimo ${ValidadorAcceso.MIN_CONTRASENA} caracteres"
                            )
                        },
                        // KeyboardType.Password: el teclado no guarda ni sugiere lo que se escribe
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = if (registro) ImeAction.Next else ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { enviar() }),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Solo al crear cuenta: repetir la contraseña para evitar errores de dedo
                    AnimatedVisibility(visible = registro) {
                        OutlinedTextField(
                            value = estado.confirmacion,
                            onValueChange = sesionViewModel::onConfirmacionChange,
                            label = { Text("Confirmar contraseña") },
                            leadingIcon = { IconoCampo(R.drawable.ic_contrasena) },
                            singleLine = true,
                            enabled = !bloqueado,
                            isError = errores.confirmacion != null,
                            visualTransformation = if (verContrasena) VisualTransformation.None else PasswordVisualTransformation(),
                            supportingText = errores.confirmacion?.let { { Text(it) } },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { enviar() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 5. Error genérico, sin detalles técnicos
                    estado.error?.let { mensaje ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "⚠️ $mensaje",
                            color = colores.onErrorContainer,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colores.errorContainer)
                                .padding(12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = enviar,
                        enabled = !bloqueado,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        when {
                            estado.verificando -> {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = colores.onPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (registro) "Creando cuenta..." else "Verificando...")
                            }
                            !registro && estado.esperaSegundos > 0 -> Text("Demasiados intentos · espera ${estado.esperaSegundos} s")
                            else -> Text(if (registro) "Crear cuenta" else "Iniciar sesión", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = sesionViewModel::cambiarModo, enabled = !estado.verificando) {
                Text(
                    if (registro) "¿Ya tienes cuenta? Inicia sesión" else "¿No tienes cuenta? Crea una",
                    color = colores.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "🔐 Conexión cifrada (HTTPS) · tu contraseña no se guarda en el teléfono",
                fontSize = 12.sp,
                color = colores.onPrimary.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Ícono de los campos; toma el color del campo (gris normal, rojo con error). */
@Composable
private fun IconoCampo(recurso: Int) {
    Icon(painterResource(recurso), contentDescription = null, modifier = Modifier.size(22.dp))
}

/** 7. Mensaje de acceso concedido (o cuenta creada) que aparece al entrar. */
@Composable
fun BienvenidaDialog(bienvenida: Bienvenida, onCerrar: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCerrar,
        icon = { Text("✅", fontSize = 36.sp) },
        title = { Text(bienvenida.titulo) },
        text = { Text(bienvenida.mensaje, color = Color.Gray) },
        confirmButton = { TextButton(onClick = onCerrar) { Text("Continuar") } }
    )
}
