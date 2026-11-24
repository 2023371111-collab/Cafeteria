package com.example.cafeteria

/**
 * Reglas para el inicio de sesión (práctica 5, AccesoSeguro).
 * Se validan ANTES de mandar algo al servidor; login.php vuelve a validar lo mismo,
 * porque una app se puede modificar y nunca hay que confiar solo en el cliente.
 */
object ValidadorAcceso {

    // Longitud definida para cada campo. Rechazar entradas enormes evita abusos
    // (cadenas que saturen memoria o el servidor) y fija un formato esperado.
    const val MAX_USUARIO = 30
    const val MIN_CONTRASENA = 8
    const val MAX_CONTRASENA = 64

    // Lista blanca para el usuario: letras, números, punto, guion y guion bajo.
    // Bloquea caracteres usados en inyecciones (' " ; < > espacios, etc.).
    private val REGEX_USUARIO = Regex("^[A-Za-z0-9._-]+$")

    // Mensajes genéricos: no dicen qué campo falló, ni cuál es el límite exacto,
    // ni si el usuario existe, ni detalles técnicos (excepciones, rutas, SQL).
    const val MSG_CAMPOS_VACIOS = "Completa todos los campos."
    const val MSG_DATOS_INVALIDOS = "Datos de acceso inválidos. Verifica e intenta de nuevo."
    const val MSG_ERROR_GENERAL = "No fue posible procesar la solicitud. Intenta más tarde."
    const val MSG_SIN_CONEXION = "No fue posible conectar. Revisa tu conexión a internet."

    /**
     * Resultado de validar. [motivoLog] describe el problema para Logcat sin incluir
     * nunca lo que escribió el usuario.
     */
    data class Rechazo(val mensaje: String, val motivoLog: String)

    /** Devuelve null si las entradas son válidas, o el rechazo con su mensaje genérico. */
    fun validar(usuario: String, contrasena: String): Rechazo? {
        val limpio = usuario.trim()
        return when {
            // 1) Ambos campos deben tener información. isBlank() también detecta un
            //    usuario que contiene ÚNICAMENTE espacios: "    " se rechaza.
            usuario.isBlank() || contrasena.isEmpty() ->
                Rechazo(MSG_CAMPOS_VACIOS, "campos vacíos")

            // 2) Rechazar entradas que superen la longitud definida.
            limpio.length > MAX_USUARIO || contrasena.length > MAX_CONTRASENA ->
                Rechazo(MSG_DATOS_INVALIDOS, "longitud excedida")

            // 3) La contraseña debe tener al menos 8 caracteres.
            contrasena.length < MIN_CONTRASENA ->
                Rechazo(MSG_DATOS_INVALIDOS, "contraseña demasiado corta")

            // 4) El usuario solo puede tener caracteres permitidos.
            !REGEX_USUARIO.matches(limpio) ->
                Rechazo(MSG_DATOS_INVALIDOS, "formato de usuario no permitido")

            else -> null
        }
    }

    // ---------------------------------------------
    // Crear cuenta: aquí sí se dice qué campo corregir, porque son datos que la
    // persona está eligiendo (no revela nada de otras cuentas ni del servidor).
    // ---------------------------------------------

    const val MIN_USUARIO = 4

    /** Error de cada campo del registro; null = el campo está bien. */
    data class ErroresRegistro(
        val nombre: String? = null,
        val usuario: String? = null,
        val contrasena: String? = null,
        val confirmacion: String? = null
    ) {
        val hayErrores: Boolean
            get() = nombre != null || usuario != null || contrasena != null || confirmacion != null

        /** Nombres de los campos con error, para Logcat (sin los valores). */
        val camposConError: String
            get() = listOfNotNull(
                nombre?.let { "nombre" }, usuario?.let { "usuario" },
                contrasena?.let { "contraseña" }, confirmacion?.let { "confirmación" }
            ).joinToString()
    }

    fun validarRegistro(nombre: String, usuario: String, contrasena: String, confirmacion: String): ErroresRegistro {
        val limpio = usuario.trim()
        return ErroresRegistro(
            // Mismas reglas del nombre que en los pedidos: solo letras, 3 a 60
            nombre = ValidadorPedido.nombre(nombre),
            usuario = when {
                limpio.isEmpty() -> "Escribe un usuario"
                limpio.length < MIN_USUARIO -> "Mínimo $MIN_USUARIO caracteres"
                limpio.length > MAX_USUARIO -> "Máximo $MAX_USUARIO caracteres"
                !REGEX_USUARIO.matches(limpio) -> "Solo letras, números, punto, guion y guion bajo"
                else -> null
            },
            contrasena = when {
                contrasena.length < MIN_CONTRASENA -> "Mínimo $MIN_CONTRASENA caracteres"
                contrasena.length > MAX_CONTRASENA -> "Máximo $MAX_CONTRASENA caracteres"
                // Para una cuenta nueva se pide una contraseña un poco más fuerte
                contrasena.none { it.isLetter() } || contrasena.none { it.isDigit() } -> "Debe tener letras y números"
                else -> null
            },
            confirmacion = when {
                confirmacion.isEmpty() -> "Repite la contraseña"
                confirmacion != contrasena -> "Las contraseñas no coinciden"
                else -> null
            }
        )
    }
}
