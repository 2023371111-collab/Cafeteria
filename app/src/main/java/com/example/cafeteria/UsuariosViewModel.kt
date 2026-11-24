package com.example.cafeteria

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Formulario y lista de la pantalla "Usuarios" (práctica 4, Room). */
data class UsuariosUiState(
    val nombre: String = "",
    val edad: String = "",
    val profesion: String = "",
    val correo: String = "",
    val errorNombre: String? = null,
    val errorEdad: String? = null,
    val errorProfesion: String? = null,
    val errorCorreo: String? = null,
    /** Usuario que se está modificando; null = el formulario registra uno nuevo. */
    val editando: Usuario? = null,
    /** Resultado de dao.obtenerTodos(); null mientras no se presione "VER usuarios". */
    val usuarios: List<Usuario>? = null,
    /** Mensaje corto para el Snackbar ("Usuario registrado", etc.). */
    val mensaje: String? = null
)

class UsuariosViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.obtener(application).usuarioDao()

    private val _ui = MutableStateFlow(UsuariosUiState())
    val ui: StateFlow<UsuariosUiState> = _ui.asStateFlow()

    fun onNombreChange(valor: String) = _ui.update { it.copy(nombre = valor.take(MAX_TEXTO), errorNombre = null) }
    fun onEdadChange(valor: String) = _ui.update { it.copy(edad = valor.filter(Char::isDigit).take(3), errorEdad = null) }
    fun onProfesionChange(valor: String) = _ui.update { it.copy(profesion = valor.take(MAX_TEXTO), errorProfesion = null) }
    fun onCorreoChange(valor: String) = _ui.update { it.copy(correo = valor.trim().take(MAX_TEXTO), errorCorreo = null) }

    /** Botón "VER usuarios": ejecuta dao.obtenerTodos() y muestra los registros. */
    fun verUsuarios() {
        viewModelScope.launch {
            val lista = dao.obtenerTodos()
            _ui.update { it.copy(usuarios = lista) }
        }
    }

    /** Registra (Caso 1) o actualiza (Caso 3) según si hay un usuario en edición. */
    fun guardar() {
        val estado = _ui.value
        val edad = estado.edad.toIntOrNull()
        val conErrores = estado.copy(
            errorNombre = if (estado.nombre.isBlank()) "Escribe el nombre" else null,
            errorEdad = when {
                edad == null -> "Escribe la edad"
                edad !in 1..120 -> "Edad entre 1 y 120"
                else -> null
            },
            errorProfesion = if (estado.profesion.isBlank()) "Escribe la carrera" else null,
            errorCorreo = if (!REGEX_CORREO.matches(estado.correo)) "Correo no válido (ej. ana@uteq.edu.mx)" else null
        )
        if (conErrores.errorNombre != null || conErrores.errorEdad != null ||
            conErrores.errorProfesion != null || conErrores.errorCorreo != null
        ) {
            _ui.value = conErrores
            return
        }

        val datos = Usuario(
            id = estado.editando?.id ?: 0,
            nombre = estado.nombre.trim(),
            edad = edad!!,
            profesion = estado.profesion.trim(),
            correo = estado.correo
        )
        viewModelScope.launch {
            val mensaje = if (estado.editando == null) {
                dao.insertar(datos)
                "✅ ${datos.nombre} registrado"
            } else {
                dao.actualizar(datos)
                "✏️ ${datos.nombre} actualizado"
            }
            // Limpia el formulario y vuelve a consultar para ver el cambio en la lista
            _ui.value = UsuariosUiState(usuarios = dao.obtenerTodos(), mensaje = mensaje)
        }
    }

    /** Pasa los datos del usuario al formulario para modificarlos. */
    fun editar(usuario: Usuario) = _ui.update {
        it.copy(
            nombre = usuario.nombre,
            edad = usuario.edad.toString(),
            profesion = usuario.profesion,
            correo = usuario.correo,
            errorNombre = null, errorEdad = null, errorProfesion = null, errorCorreo = null,
            editando = usuario
        )
    }

    fun cancelarEdicion() = _ui.update { UsuariosUiState(usuarios = it.usuarios) }

    fun eliminar(usuario: Usuario) {
        viewModelScope.launch {
            dao.eliminar(usuario)
            _ui.update { estado ->
                // Si se borra el que se estaba editando, el formulario regresa a "registrar"
                val base = if (estado.editando?.id == usuario.id) UsuariosUiState() else estado
                base.copy(usuarios = dao.obtenerTodos(), mensaje = "🗑️ ${usuario.nombre} eliminado")
            }
        }
    }

    fun mensajeMostrado() = _ui.update { it.copy(mensaje = null) }

    companion object {
        const val MAX_TEXTO = 60
        private val REGEX_CORREO = Regex("""^[\w.+-]+@[\w-]+(\.[\w-]+)+$""")
    }
}
