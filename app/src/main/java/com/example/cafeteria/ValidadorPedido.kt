package com.example.cafeteria

/**
 * Reglas para capturar un pedido. Cada función devuelve null si el valor es
 * correcto, o el mensaje que se muestra debajo del campo.
 *
 * pedidos.php vuelve a validar lo mismo en el servidor.
 */
object ValidadorPedido {

    private val REGEX_NOMBRE = Regex("^[\\p{L} .'-]{3,60}$")
    private val REGEX_TELEFONO = Regex("^\\d{10}$")
    private val REGEX_FOLIO = Regex("^CMD-[0-9A-Z]{6}$")
    const val MAX_NOTAS = 150
    const val MAX_DIRECCION = 150

    fun nombre(valor: String): String? {
        val limpio = valor.trim()
        return when {
            limpio.isEmpty() -> "Escribe tu nombre"
            limpio.length < 3 -> "Mínimo 3 letras"
            !REGEX_NOMBRE.matches(limpio) -> "Solo letras y espacios (máximo 60)"
            else -> null
        }
    }

    fun telefono(valor: String): String? = when {
        valor.isBlank() -> "Escribe tu teléfono"
        !REGEX_TELEFONO.matches(valor) -> "Debe tener 10 dígitos"
        else -> null
    }

    fun folio(valor: String): String? = when {
        valor.isBlank() -> "Escribe el folio"
        !REGEX_FOLIO.matches(valor) -> "El folio es como CMD-7K3F9Q"
        else -> null
    }

    /** "cmd7k3f9q" o "7K3F9Q" -> "CMD-7K3F9Q"; deja igual lo que no parezca folio. */
    fun normalizarFolio(valor: String): String {
        val limpio = valor.uppercase().filter { it.isLetterOrDigit() }
        val codigo = limpio.removePrefix("CMD")
        return if (codigo.length == 6) "CMD-$codigo" else valor.uppercase().trim()
    }

    fun notas(valor: String): String? =
        if (valor.trim().length > MAX_NOTAS) "Máximo $MAX_NOTAS caracteres" else null

    /** Coordenadas dentro de los rangos válidos del planeta. */
    fun ubicacion(latitud: Double, longitud: Double): String? = when {
        latitud.isNaN() || longitud.isNaN() -> "Ubicación no válida"
        latitud !in -90.0..90.0 || longitud !in -180.0..180.0 -> "Ubicación no válida"
        else -> null
    }

    /** Normaliza espacios: "  ana   lópez " -> "ana lópez". */
    fun limpiarEspacios(valor: String): String = valor.trim().replace(Regex("\\s+"), " ")
}
