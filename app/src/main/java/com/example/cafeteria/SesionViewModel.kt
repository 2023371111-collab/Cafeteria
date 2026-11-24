package com.example.cafeteria

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Formulario de inicio de sesión y de crear cuenta (práctica 5, AccesoSeguro). */
data class LoginUiState(
    /** false = iniciar sesión, true = crear cuenta. */
    val modoRegistro: Boolean = false,
    val nombre: String = "",
    val usuario: String = "",
    val contrasena: String = "",
    val confirmacion: String = "",
    val verificando: Boolean = false,
    val error: String? = null,
    /** Errores por campo, solo en modo crear cuenta. */
    val erroresRegistro: ValidadorAcceso.ErroresRegistro = ValidadorAcceso.ErroresRegistro(),
    /** Segundos que faltan para poder intentar otra vez tras varios fallos. */
    val esperaSegundos: Int = 0
)

/** Diálogo que aparece una vez al entrar. */
data class Bienvenida(val titulo: String, val mensaje: String)

class SesionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AuthRepository()

    // Se recuerdan usuario, nombre, rol y el token de sesión (vence en 12 h en el servidor)
    // para no pedir login cada vez; la contraseña nunca se guarda
    private val prefs = application.getSharedPreferences("sesion_prefs", Context.MODE_PRIVATE)

    private val _sesion = MutableStateFlow(leerSesionGuardada())
    val sesion: StateFlow<Sesion?> = _sesion.asStateFlow()

    private val _login = MutableStateFlow(LoginUiState())
    val login: StateFlow<LoginUiState> = _login.asStateFlow()

    /** Mensaje de bienvenida que se muestra una vez después de entrar. */
    private val _bienvenida = MutableStateFlow<Bienvenida?>(null)
    val bienvenida: StateFlow<Bienvenida?> = _bienvenida.asStateFlow()

    private var fallosSeguidos = 0

    fun onUsuarioChange(valor: String) {
        // Tope duro al doble del límite (evita pegar textos enormes); lo que pase del
        // límite real lo rechaza ValidadorAcceso al presionar "Iniciar sesión"
        _login.update {
            it.copy(usuario = valor.take(ValidadorAcceso.MAX_USUARIO * 2), error = null,
                erroresRegistro = it.erroresRegistro.copy(usuario = null))
        }
    }

    fun onContrasenaChange(valor: String) {
        _login.update {
            it.copy(contrasena = valor.take(ValidadorAcceso.MAX_CONTRASENA * 2), error = null,
                erroresRegistro = it.erroresRegistro.copy(contrasena = null))
        }
    }

    fun onNombreChange(valor: String) {
        _login.update { it.copy(nombre = valor.take(80), error = null, erroresRegistro = it.erroresRegistro.copy(nombre = null)) }
    }

    fun onConfirmacionChange(valor: String) {
        _login.update {
            it.copy(confirmacion = valor.take(ValidadorAcceso.MAX_CONTRASENA * 2), error = null,
                erroresRegistro = it.erroresRegistro.copy(confirmacion = null))
        }
    }

    /** Cambia entre "Iniciar sesión" y "Crear cuenta"; las contraseñas escritas se borran. */
    fun cambiarModo() {
        _login.update {
            LoginUiState(modoRegistro = !it.modoRegistro, usuario = it.usuario, esperaSegundos = it.esperaSegundos)
        }
    }

    fun crearCuenta() {
        val estado = _login.value
        if (estado.verificando) return

        Log.i(TAG, "Intento de crear cuenta")

        val errores = ValidadorAcceso.validarRegistro(estado.nombre, estado.usuario, estado.contrasena, estado.confirmacion)
        if (errores.hayErrores) {
            // Solo los NOMBRES de los campos con error, nunca lo que se escribió
            Log.w(TAG, "Registro rechazado, campos con error: ${errores.camposConError}")
            _login.update { it.copy(erroresRegistro = errores) }
            return
        }

        _login.update { it.copy(verificando = true, error = null) }
        viewModelScope.launch {
            val resultado = repository.registrar(
                ValidadorPedido.limpiarEspacios(estado.nombre), estado.usuario.trim(), estado.contrasena
            )
            when (resultado) {
                is ResultadoLogin.Exito -> {
                    Log.i(TAG, "Cuenta creada")
                    entrar(resultado.sesion, Bienvenida("Cuenta creada", "¡Bienvenido, ${resultado.sesion.nombre}! Ya puedes usar la app."))
                }
                ResultadoLogin.Rechazado -> {
                    Log.w(TAG, "Registro rechazado por el servidor")
                    _login.update { it.copy(verificando = false, error = ValidadorAcceso.MSG_DATOS_INVALIDOS) }
                }
                is ResultadoLogin.Fallo -> {
                    Log.w(TAG, "Fallo al crear la cuenta en el servidor")
                    _login.update { it.copy(verificando = false, error = resultado.mensaje) }
                }
            }
        }
    }

    /** Guarda la sesión (sin contraseña), limpia el formulario y entra a la app. */
    private fun entrar(sesion: Sesion, bienvenida: Bienvenida) {
        fallosSeguidos = 0
        prefs.edit()
            .putString("usuario", sesion.usuario)
            .putString("nombre", sesion.nombre)
            .putString("rol", sesion.rol)
            .putString("token", sesion.token)
            .apply()
        // La contraseña se borra de la memoria en cuanto ya no se necesita
        _login.value = LoginUiState()
        _bienvenida.value = bienvenida
        _sesion.value = sesion
    }

    fun iniciarSesion() {
        val estado = _login.value
        if (estado.verificando || estado.esperaSegundos > 0) return

        // Logcat: solo el EVENTO, nunca el usuario ni la contraseña
        Log.i(TAG, "Intento de inicio de sesión")

        ValidadorAcceso.validar(estado.usuario, estado.contrasena)?.let { rechazo ->
            Log.w(TAG, "Entrada rechazada: ${rechazo.motivoLog}")
            _login.update { it.copy(error = rechazo.mensaje) }
            return
        }

        _login.update { it.copy(verificando = true, error = null) }
        viewModelScope.launch {
            when (val resultado = repository.iniciarSesion(estado.usuario.trim(), estado.contrasena)) {
                is ResultadoLogin.Exito -> {
                    Log.i(TAG, "Inicio de sesión exitoso")
                    entrar(resultado.sesion, Bienvenida("Acceso concedido", "¡Bienvenido, ${resultado.sesion.nombre}!"))
                }
                ResultadoLogin.Rechazado -> {
                    fallosSeguidos++
                    Log.w(TAG, "Credenciales rechazadas por el servidor (fallo $fallosSeguidos)")
                    _login.update { it.copy(verificando = false, contrasena = "", error = ValidadorAcceso.MSG_DATOS_INVALIDOS) }
                    if (fallosSeguidos >= MAX_FALLOS) esperar()
                }
                is ResultadoLogin.Fallo -> {
                    Log.w(TAG, "Fallo al verificar con el servidor de autenticación")
                    _login.update { it.copy(verificando = false, error = resultado.mensaje) }
                }
            }
        }
    }

    /** Tras varios fallos seguidos, se bloquea el botón un rato para frenar la fuerza bruta. */
    private fun esperar() {
        viewModelScope.launch {
            for (s in SEGUNDOS_ESPERA downTo 1) {
                _login.update { it.copy(esperaSegundos = s) }
                delay(1000)
            }
            fallosSeguidos = 0
            _login.update { it.copy(esperaSegundos = 0) }
        }
    }

    fun bienvenidaMostrada() {
        _bienvenida.value = null
    }

    fun cerrarSesion() {
        Log.i(TAG, "Sesión cerrada")
        prefs.edit().clear().apply()
        _login.value = LoginUiState()
        _sesion.value = null
    }

    private fun leerSesionGuardada(): Sesion? {
        val usuario = prefs.getString("usuario", null) ?: return null
        val nombre = prefs.getString("nombre", null) ?: return null
        return Sesion(
            usuario = usuario,
            nombre = nombre,
            rol = prefs.getString("rol", null) ?: "cliente",
            token = prefs.getString("token", null).orEmpty()
        )
    }

    companion object {
        /** Etiqueta para filtrar en Logcat: tag:AccesoSeguro */
        const val TAG = "AccesoSeguro"
        private const val MAX_FALLOS = 3
        private const val SEGUNDOS_ESPERA = 30
    }
}
