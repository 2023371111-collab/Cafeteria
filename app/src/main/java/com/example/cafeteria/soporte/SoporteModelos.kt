package com.example.cafeteria.soporte

import java.util.Date

// =============================================
// Módulo "Soporte" (prácticas 7, 9 y 10)
// Incidencias / reportes compartidos en Cloud Firestore entre varios dispositivos,
// con roles (administrador, agente de atención y usuarios) y conversación por reporte.
// =============================================

/** Roles de la práctica 7. Se guardan en usuarios/{uid}.rol y los revisan las reglas de Firestore. */
enum class Rol(val valor: String, val etiqueta: String, val icono: String) {
    ADMIN("admin", "Administrador", "🛡️"),
    AGENTE("agente", "Agente de atención", "🎧"),
    USUARIO("usuario", "Usuario", "👤");

    val esPersonal: Boolean get() = this == ADMIN || this == AGENTE

    companion object {
        fun de(valor: String?) = entries.firstOrNull { it.valor == valor } ?: USUARIO
    }
}

/** Perfil de una cuenta (colección usuarios). [dispositivo] es el teléfono donde inició sesión. */
data class Perfil(
    val uid: String,
    val nombre: String,
    val correo: String,
    val rol: Rol,
    val dispositivo: String,
    val conectado: Boolean,
    val ultimaConexion: Date?
) {
    /**
     * Conectado de verdad: marcó "conectado" y su último latido fue hace menos de 2 minutos.
     * Si la app se cierra de golpe el latido deja de llegar y se considera desconectado.
     */
    fun enLinea(ahora: Long = System.currentTimeMillis()): Boolean =
        conectado && ultimaConexion != null && ahora - ultimaConexion.time < MINUTOS_LATIDO_VENCIDO * 60_000

    companion object {
        const val MINUTOS_LATIDO_VENCIDO = 2
    }
}

object Catalogos {
    val TIPOS = listOf("Falla en pedido", "Acceso perdido", "Cobro incorrecto", "Falla de la app", "Otro")
    val ESTADOS = listOf("Pendiente", "En proceso", "Resuelta")
    val PRIORIDADES = listOf("Baja", "Media", "Alta", "Crítica")

    /** A quién va la incidencia: la mesa de ayuda (agentes) o directo al administrador. */
    const val PARA_SOPORTE = "soporte"
    const val PARA_ADMIN = "admin"
}

data class Incidencia(
    /** Identificador único del reporte (ej. REP001). Es el id del documento en Firestore. */
    val id: String,
    val titulo: String,
    val tipo: String,
    /** Equipo o lugar con la falla (ej. "Terminal de caja 2", "App en Pixel 6a"). */
    val equipo: String,
    val descripcion: String,
    val estado: String,
    val prioridad: String,
    val dirigidoA: String,
    /** UID del propietario (lo comprueban las reglas: no se puede suplantar). */
    val creadoPor: String,
    val creadoPorNombre: String,
    val asignadoA: String?,
    val asignadoNombre: String?,
    val activo: Boolean,
    /** Sube en 1 con cada cambio: sirve para detectar modificaciones concurrentes. */
    val version: Long,
    val creadoEn: Date?,
    val actualizadoEn: Date?,
    val actualizadoPor: String?,
    val actualizadoPorNombre: String?,
    /** true = el cambio está guardado en este teléfono pero aún no lo confirma el servidor. */
    val pendienteDeSincronizar: Boolean = false
)

data class MensajeSoporte(
    val id: String,
    val emisorUid: String,
    val emisorNombre: String,
    val emisorRol: Rol,
    val emisorDispositivo: String,
    /** "todos", "admin", "agente" o "usuario". */
    val para: String,
    val texto: String,
    val archivoId: String?,
    val archivoNombre: String?,
    val archivoTipo: String?,
    val archivoBytes: Long,
    val enviadoEn: Date?,
    val pendiente: Boolean
)

/** Archivo elegido para adjuntar, ya leído (y comprimido si es imagen). */
data class Adjunto(val nombre: String, val tipo: String, val datos: ByteArray)

/** Validaciones del formulario de reporte (las reglas de Firestore repiten las mismas). */
object ValidadorIncidencia {
    val REGEX_ID = Regex("^[A-Z0-9_-]{3,20}$")

    data class Errores(
        val id: String? = null,
        val titulo: String? = null,
        val equipo: String? = null,
        val descripcion: String? = null
    ) {
        val hayErrores get() = listOfNotNull(id, titulo, equipo, descripcion).isNotEmpty()
    }

    fun validar(id: String, titulo: String, equipo: String, descripcion: String): Errores = Errores(
        id = when {
            id.isBlank() -> "Escribe el identificador (ej. REP001)"
            !REGEX_ID.matches(id) -> "De 3 a 20 letras MAYÚSCULAS, números, - o _"
            else -> null
        },
        titulo = when {
            titulo.isBlank() -> "El título es obligatorio"
            titulo.trim().length < 3 -> "Mínimo 3 caracteres"
            titulo.trim().length > 60 -> "Máximo 60 caracteres"
            else -> null
        },
        equipo = when {
            equipo.isBlank() -> "Indica el equipo o lugar de la falla"
            equipo.trim().length < 2 -> "Mínimo 2 caracteres"
            equipo.trim().length > 40 -> "Máximo 40 caracteres"
            else -> null
        },
        descripcion = when {
            descripcion.isBlank() -> "Describe el problema"
            descripcion.trim().length < 5 -> "Mínimo 5 caracteres"
            descripcion.trim().length > 300 -> "Máximo 300 caracteres"
            else -> null
        }
    )
}
