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
import java.util.UUID

/** Formulario de "Confirmar pedido" (captura y envío). */
data class CheckoutUiState(
    val nombre: String = "",
    val telefono: String = "",
    val notas: String = "",
    val errorNombre: String? = null,
    val errorTelefono: String? = null,
    val errorNotas: String? = null,
    val enviando: Boolean = false,
    val errorEnvio: String? = null,
    /** Ubicación de entrega opcional; null = el pedido se recoge en la cafetería. */
    val ubicacion: Ubicacion? = null,
    val buscandoUbicacion: Boolean = false,
    val buscandoDireccion: Boolean = false,
    val mensajeUbicacion: String? = null,
    /** El usuario negó el permiso: se ofrece abrir los Ajustes de la app. */
    val permisoUbicacionDenegado: Boolean = false,
    /** Pedido que el servidor acaba de guardar; mientras no sea null se muestra la confirmación. */
    val pedidoConfirmado: Order? = null,
    /**
     * Práctica 12: clave de idempotencia del pedido que se está intentando enviar. Se crea en el
     * primer intento y se CONSERVA si falla, así que reenviar (automático o con el botón) nunca
     * crea un segundo pedido si el primero sí llegó al servidor. Se borra al confirmarse.
     */
    val claveIdempotencia: String? = null,
    /** "Reintentando (2 de 3)…" mientras hay un reintento automático en curso. */
    val mensajeReintento: String? = null
) {
    val tieneErrores: Boolean
        get() = errorNombre != null || errorTelefono != null || errorNotas != null
}

/** Estados posibles de la pantalla "Pedidos" (consulta). */
sealed interface PedidosUiState {
    data object Inicio : PedidosUiState
    data object Loading : PedidosUiState
    data class Success(val pedidos: List<Order>) : PedidosUiState
    data object Empty : PedidosUiState
    data class Error(val message: String) : PedidosUiState
}

/** [busqueda] es un teléfono (solo dígitos) o un folio de comanda (lleva letras). */
data class ConsultaPedidosUi(
    val busqueda: String = "",
    val errorBusqueda: String? = null,
    val estado: PedidosUiState = PedidosUiState.Inicio
)

class OrdersViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = OrderRepository()
    private val ubicacionProvider = UbicacionProvider(application)

    // Recuerda el nombre y teléfono del último pedido para no escribirlos otra vez
    private val prefs = application.getSharedPreferences("pedidos_prefs", Context.MODE_PRIVATE)

    private val _checkout = MutableStateFlow(
        CheckoutUiState(
            nombre = prefs.getString("nombre", "").orEmpty(),
            telefono = prefs.getString("telefono", "").orEmpty()
        )
    )
    val checkout: StateFlow<CheckoutUiState> = _checkout.asStateFlow()

    private val _consulta = MutableStateFlow(ConsultaPedidosUi(busqueda = prefs.getString("telefono", "").orEmpty()))
    val consulta: StateFlow<ConsultaPedidosUi> = _consulta.asStateFlow()

    private var consultaActual: Job? = null
    private var direccionActual: Job? = null

    // ---------- Captura y envío ----------

    fun onNombreChange(valor: String) {
        _checkout.update { it.copy(nombre = valor.take(60), errorNombre = null) }
    }

    fun onTelefonoChange(valor: String) {
        _checkout.update { it.copy(telefono = valor.filter(Char::isDigit).take(10), errorTelefono = null) }
    }

    fun onNotasChange(valor: String) {
        _checkout.update { it.copy(notas = valor.take(ValidadorPedido.MAX_NOTAS), errorNotas = null) }
    }

    fun enviarPedido(carrito: List<CartItem>) {
        val actual = _checkout.value
        if (actual.enviando || carrito.isEmpty()) return // evita envíos dobles

        // 1. Validar antes de usar la red
        val validado = actual.copy(
            errorNombre = ValidadorPedido.nombre(actual.nombre),
            errorTelefono = ValidadorPedido.telefono(actual.telefono),
            errorNotas = ValidadorPedido.notas(actual.notas),
            errorEnvio = null
        )
        _checkout.value = validado
        if (validado.tieneErrores) return

        // 2. Enviar a la nube en segundo plano (misma clave en todos los intentos de este pedido)
        val clave = validado.claveIdempotencia ?: UUID.randomUUID().toString()
        _checkout.value = validado.copy(enviando = true, claveIdempotencia = clave, mensajeReintento = null)
        viewModelScope.launch {
            try {
                // Sin permiso de notificaciones no se manda el token: no tendría caso avisar
                val fcmToken = if (NotificacionesPedido.tienePermiso(getApplication())) NotificacionesPedido.tokenFcm() else null
                val pedido = Perf.medir("crearPedido") {
                    repository.crearPedido(
                        cliente = ValidadorPedido.limpiarEspacios(validado.nombre),
                        telefono = validado.telefono,
                        notas = validado.notas.trim(),
                        ubicacion = validado.ubicacion?.takeIf { ValidadorPedido.ubicacion(it.latitud, it.longitud) == null },
                        fcmToken = fcmToken,
                        carrito = carrito,
                        claveIdempotencia = clave,
                        alReintentar = { siguiente, total, _ ->
                            _checkout.update { it.copy(mensajeReintento = "Reintentando ($siguiente de $total)…") }
                        }
                    )
                }
                prefs.edit()
                    .putString("nombre", pedido.customer)
                    .putString("telefono", pedido.phone)
                    .apply()
                // La ubicación no se recuerda: se pide de nuevo en cada pedido
                _checkout.update {
                    it.copy(enviando = false, notas = "", ubicacion = null, pedidoConfirmado = pedido,
                        claveIdempotencia = null, mensajeReintento = null)
                }
                _consulta.update { it.copy(busqueda = pedido.phone, errorBusqueda = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // La clave se conserva: si el usuario reenvía, el servidor no duplica el pedido
                _checkout.update { it.copy(enviando = false, errorEnvio = e.message, mensajeReintento = null) }
            } catch (e: Exception) {
                _checkout.update { it.copy(enviando = false, errorEnvio = "Ocurrió un error inesperado.", mensajeReintento = null) }
            }
        }
    }

    fun cerrarConfirmacion() {
        _checkout.update { it.copy(pedidoConfirmado = null) }
    }

    // ---------- Ubicación de entrega (opcional) ----------

    /** La pantalla lo llama cuando el permiso ya está concedido. */
    fun obtenerUbicacion() {
        if (_checkout.value.buscandoUbicacion) return
        if (!ubicacionProvider.tienePermiso()) {
            onPermisoUbicacionDenegado()
            return
        }
        if (!ubicacionProvider.ubicacionActivada()) {
            _checkout.update { it.copy(mensajeUbicacion = "Activa la ubicación de tu teléfono e inténtalo otra vez.") }
            return
        }

        _checkout.update { it.copy(buscandoUbicacion = true, mensajeUbicacion = null, permisoUbicacionDenegado = false) }
        viewModelScope.launch {
            val ubicacion = ubicacionProvider.obtenerActual()
            if (ubicacion == null) {
                _checkout.update {
                    it.copy(
                        buscandoUbicacion = false,
                        mensajeUbicacion = "No se pudo obtener tu ubicación. Elige el punto en el mapa o envía sin ella."
                    )
                }
            } else {
                ponerUbicacion(ubicacion)
            }
        }
    }

    /** Punto elegido a mano en el mapa. No necesita permiso de ubicación. */
    fun elegirEnMapa(latitud: Double, longitud: Double) {
        if (ValidadorPedido.ubicacion(latitud, longitud) != null) return
        ponerUbicacion(Ubicacion(latitud, longitud))
    }

    /** Guarda la ubicación y busca su dirección en segundo plano. */
    private fun ponerUbicacion(ubicacion: Ubicacion) {
        _checkout.update {
            it.copy(
                ubicacion = ubicacion,
                buscandoUbicacion = false,
                buscandoDireccion = true,
                mensajeUbicacion = null,
                permisoUbicacionDenegado = false
            )
        }
        direccionActual?.cancel()
        direccionActual = viewModelScope.launch {
            val direccion = ubicacionProvider.nombreDelLugar(ubicacion.latitud, ubicacion.longitud)
            _checkout.update {
                // Solo si el usuario no cambió ni quitó la ubicación mientras se buscaba
                if (it.ubicacion == ubicacion) {
                    it.copy(ubicacion = ubicacion.copy(direccion = direccion), buscandoDireccion = false)
                } else {
                    it
                }
            }
        }
    }

    fun onPermisoUbicacionDenegado() {
        _checkout.update {
            it.copy(
                permisoUbicacionDenegado = true,
                mensajeUbicacion = "Sin permiso de ubicación. Tu pedido se envía igual; escribe tu salón en las notas."
            )
        }
    }

    fun quitarUbicacion() {
        direccionActual?.cancel()
        _checkout.update { it.copy(ubicacion = null, buscandoDireccion = false, mensajeUbicacion = null) }
    }

    // ---------- Consulta ----------

    fun onBusquedaChange(valor: String) {
        val limpio = valor.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(10)
        _consulta.update { it.copy(busqueda = limpio, errorBusqueda = null) }
    }

    fun consultar() {
        val busqueda = _consulta.value.busqueda
        val esFolio = busqueda.any(Char::isLetter)
        val valor = if (esFolio) ValidadorPedido.normalizarFolio(busqueda) else busqueda
        val error = if (esFolio) ValidadorPedido.folio(valor) else ValidadorPedido.telefono(valor)
        if (error != null) {
            _consulta.update { it.copy(errorBusqueda = error) }
            return
        }

        consultaActual?.cancel() // si había una consulta en curso, se descarta
        _consulta.update { it.copy(busqueda = valor, estado = PedidosUiState.Loading) }
        consultaActual = viewModelScope.launch {
            val estado = try {
                val pedidos = Perf.medir("consultarPedidos") {
                    if (esFolio) repository.consultarPorFolio(valor) else repository.consultarPedidos(valor)
                }
                if (pedidos.isEmpty()) PedidosUiState.Empty else PedidosUiState.Success(pedidos)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                PedidosUiState.Error(e.message ?: "Error desconocido")
            } catch (e: Exception) {
                PedidosUiState.Error("Ocurrió un error inesperado.")
            }
            _consulta.update { it.copy(estado = estado) }
        }
    }
}
