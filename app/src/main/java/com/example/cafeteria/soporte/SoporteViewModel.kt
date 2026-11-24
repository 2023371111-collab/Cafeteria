package com.example.cafeteria.soporte

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date

// =============================================
// Estado del módulo "Soporte" (prácticas 7, 9 y 10).
// =============================================

data class LoginSoporteState(
    val modoRegistro: Boolean = false,
    val nombre: String = "",
    val correo: String = "",
    val contrasena: String = "",
    val verificando: Boolean = false,
    val error: String? = null
)

data class FormularioIncidencia(
    val abierto: Boolean = false,
    val id: String = "",
    val titulo: String = "",
    val tipo: String = Catalogos.TIPOS.first(),
    val equipo: String = "",
    val descripcion: String = "",
    val prioridad: String = "Media",
    val dirigidoA: String = Catalogos.PARA_SOPORTE,
    val errores: ValidadorIncidencia.Errores = ValidadorIncidencia.Errores(),
    val guardando: Boolean = false
)

data class SoporteUiState(
    /** Revisando si ya había sesión de Firebase. */
    val iniciando: Boolean = true,
    val perfil: Perfil? = null,
    val login: LoginSoporteState = LoginSoporteState(),
    val incidencias: List<Incidencia> = emptyList(),
    val cargandoLista: Boolean = false,
    val errorLista: String? = null,
    /** true = admin y agente: la lista se actualiza sola. false = usuario: botón "Actualizar". */
    val tiempoReal: Boolean = false,
    /** La lista vino de la caché del teléfono (sin confirmar con el servidor). */
    val desdeCache: Boolean = false,
    val ultimaActualizacion: Date? = null,
    /** Todas las cuentas (solo personal): para asignar y ver quién está conectado. */
    val cuentas: List<Perfil> = emptyList(),
    val formulario: FormularioIncidencia = FormularioIncidencia(),
    val busqueda: String = "",
    val mensaje: String? = null,
    /** Código recibido por la URL preasignada (cafeteria://rol?codigo=...). */
    val invitacionPendiente: String? = null,
    val ultimaInvitacion: String? = null,
    val pruebas: List<ResultadoPrueba> = emptyList(),
    val probando: Boolean = false
)

data class DetalleState(
    val id: String? = null,
    val incidencia: Incidencia? = null,
    /** Versión del registro cuando se empezó a editar: base para detectar conflictos. */
    val original: Incidencia? = null,
    val estado: String = "",
    val prioridad: String = "",
    val descripcion: String = "",
    val guardando: Boolean = false,
    val conflicto: ResultadoGuardado.Conflicto? = null,
    /** Falló por falta de red: se ofrece guardar en el teléfono y sincronizar después. */
    val ofrecerSinConexion: Boolean = false,
    /** Hora local en que llegó el último cambio de otro dispositivo y cuánto tardó. */
    val ultimoCambioRemoto: String? = null,
    val noEncontrada: Boolean = false,
    val error: String? = null,
    val creador: Perfil? = null,
    val asignado: Perfil? = null,
    val mensajes: List<MensajeSoporte> = emptyList(),
    val ocultosHasta: Long = 0,
    val texto: String = "",
    val para: String = "todos",
    val adjunto: Adjunto? = null,
    val enviando: Boolean = false
) {
    val cambios: Map<String, Any?>
        get() {
            val o = original ?: return emptyMap()
            return buildMap {
                if (estado != o.estado) put("estado", estado)
                if (prioridad != o.prioridad) put("prioridad", prioridad)
                if (descripcion.trim() != o.descripcion) put("descripcion", descripcion.trim())
            }
        }
    val visibles: List<MensajeSoporte> get() = mensajes.filter { (it.enviadoEn?.time ?: Long.MAX_VALUE) > ocultosHasta }
    val puedeEnviar: Boolean get() = (texto.isNotBlank() || adjunto != null) && texto.trim().length <= MAX_TEXTO && !enviando

    companion object {
        const val MAX_TEXTO = 200
    }
}

/** Evento para abrir un adjunto descargado con otra app. */
data class ArchivoParaAbrir(val uri: Uri, val tipo: String)

class SoporteViewModel(application: Application) : AndroidViewModel(application) {

    val repo = SoporteRepository(application)
    private val pruebasSeguridad = PruebasSeguridad(application, repo)
    private val prefs = application.getSharedPreferences("soporte_prefs", Context.MODE_PRIVATE)

    private val _estado = MutableStateFlow(SoporteUiState())
    val estado: StateFlow<SoporteUiState> = _estado.asStateFlow()

    private val _detalle = MutableStateFlow(DetalleState())
    val detalle: StateFlow<DetalleState> = _detalle.asStateFlow()

    private val _archivo = MutableStateFlow<ArchivoParaAbrir?>(null)
    val archivo: StateFlow<ArchivoParaAbrir?> = _archivo.asStateFlow()

    private var escuchaPerfil: ListenerRegistration? = null
    private var escuchaLista: ListenerRegistration? = null
    private var escuchaCuentas: ListenerRegistration? = null
    private var escuchaDetalle: ListenerRegistration? = null
    private var escuchaMensajes: ListenerRegistration? = null
    private var escuchasParticipantes: List<ListenerRegistration> = emptyList()
    private var latido: Job? = null
    private var enPrimerPlano = true
    private var sincronizarEdicion = false

    init {
        if (repo.auth.currentUser != null) cargarPerfil() else _estado.update { it.copy(iniciando = false) }
    }

    // ---------------------------------------------
    // Sesión (práctica 10, fase 1)
    // ---------------------------------------------

    fun onLogin(cambio: LoginSoporteState.() -> LoginSoporteState) =
        _estado.update { it.copy(login = it.login.cambio().copy(error = null)) }

    fun cambiarModoLogin() = _estado.update { it.copy(login = LoginSoporteState(modoRegistro = !it.login.modoRegistro, correo = it.login.correo)) }

    fun entrar() {
        val login = _estado.value.login
        if (login.verificando) return
        val correo = login.correo.trim()
        val error = when {
            correo.isBlank() || login.contrasena.isEmpty() || (login.modoRegistro && login.nombre.isBlank()) -> "Completa todos los campos."
            !android.util.Patterns.EMAIL_ADDRESS.matcher(correo).matches() -> "Escribe un correo válido."
            login.contrasena.length < 6 -> "La contraseña debe tener al menos 6 caracteres."
            login.modoRegistro && login.nombre.trim().length !in 2..40 -> "El nombre debe tener de 2 a 40 caracteres."
            else -> null
        }
        if (error != null) {
            _estado.update { it.copy(login = it.login.copy(error = error)) }
            return
        }
        _estado.update { it.copy(login = it.login.copy(verificando = true)) }
        Log.i(TAG, "Intento de inicio de sesión en Firebase")
        viewModelScope.launch {
            try {
                if (login.modoRegistro) repo.crearCuenta(login.nombre, correo, login.contrasena)
                else repo.iniciarSesion(correo, login.contrasena)
                Log.i(TAG, "SEG-01 Inicio de sesión exitoso")
                _estado.update { it.copy(login = LoginSoporteState()) }
                cargarPerfil()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = SoporteRepository.traducirAuth(e)
                Log.w(TAG, "SEG-02 Inicio de sesión rechazado [${err.codigo}]")
                _estado.update { it.copy(login = it.login.copy(verificando = false, contrasena = "", error = err.message)) }
            }
        }
    }

    private fun cargarPerfil() {
        _estado.update { it.copy(iniciando = true) }
        viewModelScope.launch {
            try {
                val perfil = repo.asegurarPerfil()
                aplicarPerfil(perfil)
                repo.marcarConexion(true)
                iniciarLatido()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update { it.copy(iniciando = false, perfil = null, mensaje = SoporteRepository.traducir(e).message) }
            }
        }
    }

    /** Cuando cambia el rol (ej. el admin lo cambió o se aceptó una invitación) se rehace la consulta. */
    private fun aplicarPerfil(perfil: Perfil) {
        val anterior = _estado.value.perfil
        _estado.update { it.copy(iniciando = false, perfil = perfil) }
        if (escuchaPerfil == null) {
            escuchaPerfil = repo.escucharPerfil(perfil.uid) { nuevo -> nuevo?.let { aplicarPerfil(it) } }
        }
        if (anterior?.rol != perfil.rol || anterior.uid != perfil.uid) {
            if (anterior != null && anterior.uid == perfil.uid) {
                _estado.update { it.copy(mensaje = "${perfil.rol.icono} Tu rol ahora es: ${perfil.rol.etiqueta}") }
            }
            iniciarLista(perfil)
        }
    }

    fun cerrarSesion() {
        val perfil = _estado.value.perfil
        viewModelScope.launch {
            // Desconexión del agente: sus incidencias abiertas regresan al administrador
            if (perfil?.rol == Rol.AGENTE) repo.soltarAsignadas(perfil)
            repo.marcarConexion(false)
            detenerTodo()
            repo.cerrarSesion()
            Log.i(TAG, "Sesión de Firebase cerrada")
            _estado.value = SoporteUiState(iniciando = false)
            _detalle.value = DetalleState()
        }
    }

    private fun detenerTodo() {
        latido?.cancel()
        listOf(escuchaPerfil, escuchaLista, escuchaCuentas, escuchaDetalle, escuchaMensajes).forEach { it?.remove() }
        escuchasParticipantes.forEach { it.remove() }
        escuchaPerfil = null; escuchaLista = null; escuchaCuentas = null; escuchaDetalle = null; escuchaMensajes = null
        escuchasParticipantes = emptyList()
    }

    // ---------------------------------------------
    // Presencia: "conectado" + latido cada minuto (práctica 7: desconexión)
    // ---------------------------------------------

    /** MainActivity avisa cuando la app pasa a primer plano o se va al fondo. */
    fun primerPlano(activo: Boolean) {
        enPrimerPlano = activo
        if (_estado.value.perfil == null) return
        if (activo) iniciarLatido() else {
            latido?.cancel()
            viewModelScope.launch { repo.marcarConexion(false) }
        }
    }

    private fun iniciarLatido() {
        latido?.cancel()
        if (!enPrimerPlano) return
        latido = viewModelScope.launch {
            while (true) {
                repo.marcarConexion(true)
                delay(60_000)
            }
        }
    }

    // ---------------------------------------------
    // Lista de incidencias
    // ---------------------------------------------

    private fun iniciarLista(perfil: Perfil) {
        escuchaLista?.remove(); escuchaLista = null
        escuchaCuentas?.remove(); escuchaCuentas = null
        if (perfil.rol.esPersonal) {
            // Comprobación en tiempo real solo para el administrador y el agente
            _estado.update { it.copy(tiempoReal = true, cargandoLista = true, errorLista = null) }
            escuchaLista = repo.escucharIncidencias(perfil,
                alCambiar = { lista, cache ->
                    _estado.update { it.copy(incidencias = lista, desdeCache = cache, cargandoLista = false, errorLista = null, ultimaActualizacion = Date()) }
                },
                alFallar = { e -> _estado.update { it.copy(cargandoLista = false, errorLista = e.message) } })
            escuchaCuentas = repo.escucharUsuarios(
                alCambiar = { cuentas -> _estado.update { it.copy(cuentas = cuentas) } },
                alFallar = { })
        } else {
            _estado.update { it.copy(tiempoReal = false, cuentas = emptyList()) }
            actualizarLista()
        }
    }

    /** Consulta manual (usuarios, o para forzar en el personal). */
    fun actualizarLista() {
        val perfil = _estado.value.perfil ?: return
        _estado.update { it.copy(cargandoLista = true, errorLista = null) }
        viewModelScope.launch {
            try {
                val (lista, cache) = repo.consultarIncidencias(perfil)
                _estado.update { it.copy(incidencias = lista, desdeCache = cache, cargandoLista = false, ultimaActualizacion = Date()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update { it.copy(cargandoLista = false, errorLista = SoporteRepository.traducir(e).message) }
            }
        }
    }

    fun onBusqueda(valor: String) = _estado.update { it.copy(busqueda = valor.uppercase().take(20)) }

    /** Busca por ID en el servidor (TC06: un ID que no existe no bloquea la app). */
    fun buscarPorId() {
        val id = _estado.value.busqueda.trim()
        if (id.isBlank()) return
        abrirDetalle(id)
    }

    // ---------------------------------------------
    // Alta (práctica 7 TC01-TC03)
    // ---------------------------------------------

    fun abrirFormulario() {
        val sugerido = "REP" + (100..999).random()
        _estado.update { it.copy(formulario = FormularioIncidencia(abierto = true, id = sugerido)) }
    }

    fun cerrarFormulario() = _estado.update { it.copy(formulario = FormularioIncidencia()) }

    fun onFormulario(cambio: FormularioIncidencia.() -> FormularioIncidencia) =
        _estado.update { it.copy(formulario = it.formulario.cambio().copy(errores = ValidadorIncidencia.Errores())) }

    fun guardarFormulario() {
        val perfil = _estado.value.perfil ?: return
        val f = _estado.value.formulario
        if (f.guardando) return
        val errores = ValidadorIncidencia.validar(f.id.trim(), f.titulo, f.equipo, f.descripcion)
        if (errores.hayErrores) {
            Log.w(TAG, "Alta rechazada por validación: ${listOfNotNull(errores.id?.let { "id" }, errores.titulo?.let { "titulo" }, errores.equipo?.let { "equipo" }, errores.descripcion?.let { "descripcion" })}")
            _estado.update { it.copy(formulario = f.copy(errores = errores), mensaje = "⚠️ Revisa los campos marcados en rojo") }
            return
        }
        _estado.update { it.copy(formulario = f.copy(guardando = true)) }
        viewModelScope.launch {
            try {
                repo.crear(perfil, f.id.trim(), f.titulo, f.tipo, f.equipo, f.descripcion, f.prioridad, f.dirigidoA)
                Log.i(TAG, "Incidencia ${f.id} creada en Firestore")
                _estado.update { it.copy(formulario = FormularioIncidencia(), mensaje = "✅ Reporte ${f.id.trim()} guardado en Firestore") }
                if (!_estado.value.tiempoReal) actualizarLista()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = SoporteRepository.traducir(e)
                Log.w(TAG, "Alta de ${f.id} fallida [${err.codigo}]")
                _estado.update {
                    it.copy(
                        formulario = f.copy(guardando = false,
                            errores = if (err.codigo == "ALREADY_EXISTS") ValidadorIncidencia.Errores(id = "Ese ID ya existe") else f.errores),
                        mensaje = err.message
                    )
                }
            }
        }
    }

    // ---------------------------------------------
    // Detalle, edición y concurrencia (práctica 9)
    // ---------------------------------------------

    fun abrirDetalle(id: String) {
        cerrarDetalle()
        _detalle.value = DetalleState(id = id, ocultosHasta = prefs.getLong("ocultos_$id", 0))
        sincronizarEdicion = true
        escuchaDetalle = repo.escucharIncidencia(id,
            alCambiar = { inc -> recibirIncidencia(inc) },
            alFallar = { e ->
                Log.w(TAG, "Lectura de $id rechazada [${e.codigo}]")
                _detalle.update { it.copy(error = e.message) }
            })
    }

    private fun recibirIncidencia(inc: Incidencia?) {
        if (inc == null) {
            _detalle.update { it.copy(noEncontrada = true, incidencia = null) }
            return
        }
        val antes = _detalle.value.incidencia
        val miUid = _estado.value.perfil?.uid
        val remoto = if (antes != null && inc.version != antes.version && !inc.pendienteDeSincronizar && inc.actualizadoPor != miUid) {
            val ahora = Date()
            val tardo = inc.actualizadoEn?.let { (ahora.time - it.time) / 1000.0 }
            val quien = inc.actualizadoPorNombre ?: "?"
            "v${inc.version} por $quien · recibido ${hora(ahora)}" + (tardo?.let { " (≈${"%.1f".format(it)} s)" } ?: "")
        } else _detalle.value.ultimoCambioRemoto
        _detalle.update { d ->
            var nuevo = d.copy(incidencia = inc, noEncontrada = false, error = null, ultimoCambioRemoto = remoto)
            if (sincronizarEdicion || d.original == null) {
                nuevo = nuevo.copy(original = inc, estado = inc.estado, prioridad = inc.prioridad, descripcion = inc.descripcion)
                sincronizarEdicion = false
            }
            nuevo
        }
        if (antes == null) {
            escucharConversacion(inc.id)
            escucharParticipantes(inc)
        } else if (antes.asignadoA != inc.asignadoA) {
            escucharParticipantes(inc)
        }
        if (miUid != null && antes == null) Log.i(TAG, "Incidencia ${inc.id} leída (v${inc.version})")
    }

    fun cerrarDetalle() {
        escuchaDetalle?.remove(); escuchaDetalle = null
        escuchaMensajes?.remove(); escuchaMensajes = null
        escuchasParticipantes.forEach { it.remove() }; escuchasParticipantes = emptyList()
        _detalle.value = DetalleState()
    }

    fun onEdicion(cambio: DetalleState.() -> DetalleState) = _detalle.update { it.cambio() }

    /** Descarta lo que se estaba editando y toma la versión actual del servidor. */
    fun recargarEdicion() {
        val inc = _detalle.value.incidencia ?: return
        _detalle.update { it.copy(original = inc, estado = inc.estado, prioridad = inc.prioridad, descripcion = inc.descripcion, conflicto = null, ofrecerSinConexion = false) }
    }

    fun guardarEdicion(forzar: Boolean = false) = guardarCambios(_detalle.value.cambios, forzar)

    private fun guardarCambios(cambios: Map<String, Any?>, forzar: Boolean = false, etiqueta: String = "Cambios guardados") {
        val perfil = _estado.value.perfil ?: return
        val d = _detalle.value
        val original = d.original ?: return
        if (cambios.isEmpty() || d.guardando) return
        if (cambios.containsKey("descripcion") && (cambios["descripcion"] as String).length !in 5..300) {
            _estado.update { it.copy(mensaje = "⚠️ La descripción debe tener de 5 a 300 caracteres") }
            return
        }
        _detalle.update { it.copy(guardando = true, conflicto = null, ofrecerSinConexion = false) }
        viewModelScope.launch {
            try {
                when (val r = repo.guardarCambios(perfil, original, cambios, forzar)) {
                    is ResultadoGuardado.Guardado -> {
                        Log.i(TAG, "${original.id} actualizada a v${r.version}: ${cambios.keys}")
                        sincronizarEdicion = true
                        _detalle.update { it.copy(guardando = false) }
                        recargarEdicion()
                        _estado.update { it.copy(mensaje = "✅ $etiqueta (versión ${r.version})") }
                        if (!_estado.value.tiempoReal) actualizarLista()
                    }
                    is ResultadoGuardado.Conflicto -> {
                        Log.w(TAG, "Conflicto en ${original.id}: ${r.campos.keys}")
                        _detalle.update { it.copy(guardando = false, conflicto = r) }
                    }
                    ResultadoGuardado.NoExiste -> _detalle.update { it.copy(guardando = false, noEncontrada = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = SoporteRepository.traducir(e)
                Log.w(TAG, "Guardado de ${original.id} fallido [${err.codigo}]")
                _detalle.update { it.copy(guardando = false, ofrecerSinConexion = err.codigo == "UNAVAILABLE" || err.codigo == "RED") }
                _estado.update { it.copy(mensaje = err.message) }
            }
        }
    }

    /** Sin red: el cambio queda en el teléfono como "pendiente" y Firestore lo manda al reconectar. */
    fun guardarSinConexion() {
        val perfil = _estado.value.perfil ?: return
        val d = _detalle.value
        val original = d.original ?: return
        repo.guardarSinConexion(perfil, original.id, d.cambios)
        sincronizarEdicion = true
        _detalle.update { it.copy(ofrecerSinConexion = false) }
        _estado.update { it.copy(mensaje = "⏳ Guardado SOLO en este teléfono: se enviará al volver la conexión") }
        if (!_estado.value.tiempoReal) actualizarLista()
    }

    fun asignar(agente: Perfil?) = guardarCambios(
        mapOf("asignadoA" to agente?.uid, "asignadoNombre" to agente?.nombre), forzar = true,
        etiqueta = if (agente == null) "Asignación quitada" else "Asignada a ${agente.nombre}"
    )

    fun eliminar() {
        val perfil = _estado.value.perfil ?: return
        val original = _detalle.value.incidencia ?: return
        viewModelScope.launch {
            try {
                repo.eliminar(perfil, original)
                Log.i(TAG, "${original.id} dada de baja (activo = false)")
                _estado.update { it.copy(mensaje = "🗑️ ${original.id} eliminado (ya no aparece como activo)") }
                cerrarDetalle()
                if (!_estado.value.tiempoReal) actualizarLista()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update { it.copy(mensaje = SoporteRepository.traducir(e).message) }
            }
        }
    }

    // ---------------------------------------------
    // Conversación (práctica 7)
    // ---------------------------------------------

    private fun escucharConversacion(id: String) {
        escuchaMensajes?.remove()
        escuchaMensajes = repo.escucharMensajes(id,
            alCambiar = { lista -> _detalle.update { it.copy(mensajes = lista) } },
            alFallar = { e -> _detalle.update { it.copy(error = e.message) } })
    }

    /** Los dos dispositivos de la conversación: quien reportó y quien atiende. */
    private fun escucharParticipantes(inc: Incidencia) {
        escuchasParticipantes.forEach { it.remove() }
        escuchasParticipantes = listOfNotNull(
            repo.escucharPerfil(inc.creadoPor) { p -> _detalle.update { it.copy(creador = p) } },
            inc.asignadoA?.let { uid -> repo.escucharPerfil(uid) { p -> _detalle.update { it.copy(asignado = p) } } }
        )
        if (inc.asignadoA == null) _detalle.update { it.copy(asignado = null) }
    }

    fun onTexto(valor: String) = _detalle.update { it.copy(texto = valor.take(DetalleState.MAX_TEXTO)) }

    fun onPara(valor: String) = _detalle.update { it.copy(para = valor) }

    fun quitarAdjunto() = _detalle.update { it.copy(adjunto = null) }

    fun enviarMensaje() {
        val perfil = _estado.value.perfil ?: return
        val d = _detalle.value
        val id = d.incidencia?.id ?: return
        if (!d.puedeEnviar) {
            _estado.update { it.copy(mensaje = "⚠️ Escribe un mensaje o adjunta un archivo") }
            return
        }
        _detalle.update { it.copy(enviando = true) }
        viewModelScope.launch {
            try {
                repo.enviarMensaje(perfil, id, d.para, d.texto.trim().replace(Regex("\\s+"), " "), d.adjunto)
                _detalle.update { it.copy(enviando = false, texto = "", adjunto = null) }
                _estado.update { it.copy(mensaje = "✅ Mensaje enviado" + (d.adjunto?.let { " con ${it.nombre}" } ?: "")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // El texto y el archivo se conservan para reintentar
                _detalle.update { it.copy(enviando = false) }
                _estado.update { it.copy(mensaje = SoporteRepository.traducir(e).message) }
            }
        }
    }

    /** Limpiar historial: oculta en este teléfono los mensajes hasta ahora (en Firestore quedan). */
    fun limpiarHistorial() {
        val id = _detalle.value.id ?: return
        val ahora = System.currentTimeMillis()
        prefs.edit().putLong("ocultos_$id", ahora).apply()
        _detalle.update { it.copy(ocultosHasta = ahora) }
        _estado.update { it.copy(mensaje = "🧹 Historial limpiado en este dispositivo") }
    }

    /** Lee el archivo elegido; las fotos se reducen para que quepan en un documento de Firestore. */
    fun adjuntar(uri: Uri) {
        viewModelScope.launch {
            try {
                val adjunto = withContext(Dispatchers.IO) { leerAdjunto(uri) }
                _detalle.update { it.copy(adjunto = adjunto) }
            } catch (e: Exception) {
                _estado.update { it.copy(mensaje = e.message ?: "No se pudo leer el archivo.") }
            }
        }
    }

    private fun leerAdjunto(uri: Uri): Adjunto {
        val resolver = getApplication<Application>().contentResolver
        val tipo = resolver.getType(uri) ?: "application/octet-stream"
        val nombre = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "archivo"
        val datos = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("No se pudo leer el archivo.")
        // Una foto grande se reduce y se convierte a JPEG
        if (tipo.startsWith("image/") && datos.size > MAX_ADJUNTO) {
            val jpeg = comprimirImagen(datos)
            if (jpeg.size <= MAX_ADJUNTO) return Adjunto(nombre.substringBeforeLast('.').take(70) + ".jpg", "image/jpeg", jpeg)
        }
        if (datos.size > MAX_ADJUNTO) throw Exception("El archivo pesa ${datos.size / 1024} KB; el máximo es ${MAX_ADJUNTO / 1024} KB.")
        return Adjunto(nombre.take(80), tipo, datos)
    }

    private fun comprimirImagen(original: ByteArray): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(original, 0, original.size) ?: return original
        val escala = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
        val reducida = Bitmap.createScaledBitmap(bmp, (bmp.width * escala).toInt(), (bmp.height * escala).toInt(), true)
        var calidad = 80
        var salida: ByteArray
        do {
            salida = ByteArrayOutputStream().also { reducida.compress(Bitmap.CompressFormat.JPEG, calidad, it) }.toByteArray()
            calidad -= 15
        } while (salida.size > MAX_ADJUNTO && calidad > 10)
        return salida
    }

    /** Descarga el adjunto, lo guarda en la caché y lo entrega para abrirlo con otra app. */
    fun abrirAdjunto(mensaje: MensajeSoporte) {
        val idInc = _detalle.value.incidencia?.id ?: return
        val archivoId = mensaje.archivoId ?: return
        viewModelScope.launch {
            try {
                val bytes = repo.descargarArchivo(idInc, archivoId)
                val app = getApplication<Application>()
                val uri = withContext(Dispatchers.IO) {
                    val carpeta = File(app.cacheDir, "adjuntos").apply { mkdirs() }
                    val archivo = File(carpeta, (mensaje.archivoNombre ?: "archivo").replace(Regex("[^\\w.\\-]"), "_"))
                    archivo.writeBytes(bytes)
                    FileProvider.getUriForFile(app, "${app.packageName}.archivos", archivo)
                }
                _archivo.value = ArchivoParaAbrir(uri, mensaje.archivoTipo ?: "application/octet-stream")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update { it.copy(mensaje = SoporteRepository.traducir(e).message) }
            }
        }
    }

    fun archivoAbierto() { _archivo.value = null }

    // ---------------------------------------------
    // Roles e invitaciones (práctica 7)
    // ---------------------------------------------

    fun cambiarRol(cuenta: Perfil, rol: Rol) {
        viewModelScope.launch {
            try {
                repo.cambiarRol(cuenta.uid, rol)
                _estado.update { it.copy(mensaje = "${rol.icono} ${cuenta.nombre} ahora es ${rol.etiqueta}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update { it.copy(mensaje = SoporteRepository.traducir(e).message) }
            }
        }
    }

    fun crearInvitacion(rol: Rol) {
        viewModelScope.launch {
            try {
                val url = repo.crearInvitacion(rol)
                _estado.update { it.copy(ultimaInvitacion = url, mensaje = "🔗 Invitación de ${rol.etiqueta} creada (válida 24 h, un solo uso)") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update { it.copy(mensaje = SoporteRepository.traducir(e).message) }
            }
        }
    }

    /** Llega desde la URL preasignada (MainActivity) o escribiendo el código a mano. */
    fun recibirInvitacion(codigo: String) =
        _estado.update { it.copy(invitacionPendiente = codigo.trim().uppercase().filter { c -> c.isLetterOrDigit() }.take(12)) }

    fun descartarInvitacion() = _estado.update { it.copy(invitacionPendiente = null) }

    fun aceptarInvitacion() {
        val codigo = _estado.value.invitacionPendiente ?: return
        viewModelScope.launch {
            try {
                val rol = repo.aceptarInvitacion(codigo)
                _estado.update { it.copy(invitacionPendiente = null, mensaje = "${rol.icono} Invitación aceptada: ahora eres ${rol.etiqueta}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update { it.copy(invitacionPendiente = null, mensaje = SoporteRepository.traducir(e).message) }
            }
        }
    }

    // ---------------------------------------------
    // Pruebas de seguridad (práctica 10)
    // ---------------------------------------------

    fun probar(prueba: suspend PruebasSeguridad.(Perfil) -> List<ResultadoPrueba>) {
        val perfil = _estado.value.perfil ?: return
        if (_estado.value.probando) return
        _estado.update { it.copy(probando = true) }
        viewModelScope.launch {
            val resultados = try {
                pruebasSeguridad.prueba(perfil)
            } catch (e: Exception) {
                emptyList()
            }
            _estado.update { it.copy(probando = false, pruebas = resultados + it.pruebas) }
        }
    }

    fun limpiarPruebas() = _estado.update { it.copy(pruebas = emptyList()) }

    fun mensajeMostrado() = _estado.update { it.copy(mensaje = null) }

    override fun onCleared() {
        detenerTodo()
        super.onCleared()
    }

    companion object {
        const val TAG = "SoporteFS"
        /** Tamaño máximo del adjunto (cabe en un documento de 1 MB ya en base64). */
        const val MAX_ADJUNTO = 700 * 1024

        fun hora(fecha: Date?): String =
            fecha?.let { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(it) } ?: "--:--:--"
    }
}
