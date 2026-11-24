package com.example.cafeteria

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// =============================================
// Práctica 6 (MobileConnect): estado de la pantalla de avisos.
// En Jetpack Compose la pantalla solo DIBUJA este estado: cuando cambia (llega un aviso,
// se escribe texto, cambia el emisor) Compose vuelve a dibujar lo necesario.
// =============================================

/** Frases predefinidas para no escribir: el cliente y el barista tienen las suyas. */
val NOTAS_RAPIDAS = mapOf(
    Emisor.CLIENTE to listOf("¿Ya está mi pedido?", "Voy en camino", "Ya llegué al mostrador", "¿Pueden cambiar la leche?"),
    Emisor.BARISTA to listOf("Ya lo estamos preparando", "¡Tu pedido está listo!", "Faltan 5 minutos", "No hay ese ingrediente")
)

const val MAX_TEXTO_AVISO = 100

data class ChatUiState(
    val abierta: Boolean = false,
    val folio: String = "",
    /** Teléfono con el que se pidió: lo necesita el cliente para demostrar que es su pedido. */
    val telefono: String? = null,
    /** Token del barista (X-Sesion); null si quien abre no es barista. */
    val sesion: String? = null,
    /** Dispositivo emisor seleccionado. */
    val emisor: Emisor = Emisor.CLIENTE,
    val conversacion: Conversacion? = null,
    val mensajes: List<Mensaje> = emptyList(),
    /** "Limpiar historial": se ocultan los avisos hasta este id (en la base de datos siguen). */
    val ocultosHasta: Int = 0,
    val texto: String = "",
    val cargando: Boolean = false,
    val enviando: Boolean = false,
    val error: String? = null,
    /** Notificación visual (Snackbar) después de enviar o al fallar. */
    val snackbar: String? = null
) {
    val puedeSerCliente: Boolean get() = telefono != null
    val puedeSerBarista: Boolean get() = sesion != null
    val visibles: List<Mensaje> get() = mensajes.filter { it.id > ocultosHasta }
    /** Contador de mensajes enviados por cada dispositivo (de los que se ven). */
    val contador: Map<Emisor, Int> get() = Emisor.entries.associateWith { e -> visibles.count { it.emisor == e } }
    val entregado: Boolean get() = conversacion?.estadoPedido == "entregado"
    /** El botón solo se activa con texto real (sin contar espacios), hasta 100 caracteres. */
    val puedeEnviar: Boolean
        get() = texto.isNotBlank() && texto.trim().length <= MAX_TEXTO_AVISO && !enviando && !entregado &&
            (emisor == Emisor.CLIENTE && puedeSerCliente || emisor == Emisor.BARISTA && puedeSerBarista)
}

data class CajonesUiState(
    val cargando: Boolean = false,
    val pila: PilaCajones? = null,
    val error: String? = null,
    val liberando: String? = null,
    val mensaje: String? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MensajesRepository()
    // Solo se recuerda hasta qué aviso se "limpió" el historial en este teléfono
    private val prefs = application.getSharedPreferences("avisos_prefs", Context.MODE_PRIVATE)

    private val _chat = MutableStateFlow(ChatUiState())
    val chat: StateFlow<ChatUiState> = _chat.asStateFlow()

    private val _cajones = MutableStateFlow(CajonesUiState())
    val cajones: StateFlow<CajonesUiState> = _cajones.asStateFlow()

    private var consultaJob: Job? = null

    /**
     * Abre la conversación de un pedido. Desde "Pedidos" se conoce el teléfono (puede ser Cliente);
     * si la cuenta es de barista también puede escribir como Barista.
     */
    fun abrir(folio: String, telefono: String?, sesionBarista: String?) {
        _chat.value = ChatUiState(
            abierta = true,
            folio = folio,
            telefono = telefono,
            sesion = sesionBarista,
            emisor = if (telefono != null) Emisor.CLIENTE else Emisor.BARISTA,
            ocultosHasta = prefs.getInt("ocultos_$folio", 0),
            cargando = true
        )
        actualizar()
    }

    fun cerrar() {
        consultaJob?.cancel()
        _chat.value = ChatUiState()
    }

    fun seleccionarEmisor(emisor: Emisor) = _chat.update { it.copy(emisor = emisor) }

    fun onTextoChange(valor: String) = _chat.update { it.copy(texto = valor.take(MAX_TEXTO_AVISO)) }

    /** Una nota rápida llena el campo; se puede editar antes de enviar. */
    fun usarNota(nota: String) = _chat.update { it.copy(texto = nota) }

    /** Consulta los avisos nuevos (la pantalla la llama cada 3 s mientras está abierta). */
    fun actualizar() {
        val estado = _chat.value
        if (!estado.abierta || consultaJob?.isActive == true) return
        val desde = estado.mensajes.lastOrNull()?.id ?: 0
        consultaJob = viewModelScope.launch {
            try {
                val conv = repository.conversacion(estado.folio, estado.telefono.takeIf { estado.sesion == null }, estado.sesion, desde)
                aplicar(conv)
                _chat.update { it.copy(cargando = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _chat.update { it.copy(cargando = false, error = e.message) }
            } catch (e: Exception) {
                _chat.update { it.copy(cargando = false, error = "Ocurrió un error inesperado.") }
            }
        }
    }

    /** Junta los avisos nuevos sin repetir los que ya se tenían. */
    private fun aplicar(conv: Conversacion) {
        _chat.update { actual ->
            val ids = actual.mensajes.mapTo(HashSet()) { it.id }
            val todos = (actual.mensajes + conv.mensajes.filter { it.id !in ids }).sortedBy { it.id }
            actual.copy(conversacion = conv.copy(mensajes = emptyList()), mensajes = todos)
        }
    }

    fun enviar() {
        val estado = _chat.value
        // Validación de mensajes vacíos: con puros espacios no se envía nada
        if (!estado.puedeEnviar) {
            if (estado.texto.isBlank()) _chat.update { it.copy(snackbar = "⚠️ Escribe un aviso o elige una nota rápida") }
            return
        }
        val texto = estado.texto.trim().replace(Regex("\\s+"), " ")
        _chat.update { it.copy(enviando = true) }
        viewModelScope.launch {
            try {
                val esBarista = estado.emisor == Emisor.BARISTA
                val conv = repository.enviar(
                    folio = estado.folio,
                    telefono = if (esBarista) null else estado.telefono,
                    sesion = if (esBarista) estado.sesion else null,
                    emisor = estado.emisor,
                    texto = texto
                )
                aplicar(conv)
                val donde = when {
                    conv.cajon != null -> "cajón ${conv.cajon}"
                    conv.posicionEspera != null -> "en espera, posición ${conv.posicionEspera}"
                    else -> estado.folio
                }
                _chat.update { it.copy(enviando = false, texto = "", snackbar = "✅ Aviso enviado ($donde)") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sin conexión o rechazo: el texto se conserva para reintentar
                val motivo = (e as? ApiException)?.message ?: "Ocurrió un error inesperado."
                _chat.update { it.copy(enviando = false, snackbar = "📡 $motivo") }
            }
        }
    }

    /** Botón "Limpiar historial": solo esconde los avisos en este teléfono; en la base quedan. */
    fun limpiarHistorial() {
        val ultimo = _chat.value.mensajes.lastOrNull()?.id ?: return
        prefs.edit().putInt("ocultos_${_chat.value.folio}", ultimo).apply()
        _chat.update { it.copy(ocultosHasta = ultimo, snackbar = "🧹 Historial limpiado en este dispositivo") }
    }

    fun snackbarMostrado() = _chat.update { it.copy(snackbar = null) }

    // ---------- Vista del barista: pila de cajones ----------

    fun cargarCajones(sesion: String, silencioso: Boolean = false) {
        if (_cajones.value.cargando) return
        if (!silencioso) _cajones.update { it.copy(cargando = true) }
        viewModelScope.launch {
            try {
                val pila = repository.cajones(sesion)
                _cajones.update { it.copy(cargando = false, pila = pila, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _cajones.update { it.copy(cargando = false, error = (e as? ApiException)?.message ?: "Ocurrió un error inesperado.") }
            }
        }
    }

    fun liberar(sesion: String, letra: String) {
        if (_cajones.value.liberando != null) return
        _cajones.update { it.copy(liberando = letra) }
        viewModelScope.launch {
            try {
                val pila = repository.liberar(sesion, letra)
                _cajones.update { it.copy(liberando = null, pila = pila, mensaje = "🔓 Cajón $letra liberado") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _cajones.update { it.copy(liberando = null, mensaje = "📡 ${(e as? ApiException)?.message ?: "Error inesperado"}") }
            }
        }
    }

    fun mensajeCajonesMostrado() = _cajones.update { it.copy(mensaje = null) }
}
