package com.example.cafeteria

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pestaña "Barista": pedidos pendientes y botones para marcarlos listos / entregados. */
data class BaristaUiState(
    val cargando: Boolean = false,
    val pedidos: List<Order> = emptyList(),
    val error: String? = null,
    /** Folios con un cambio de estado en curso (su botón se deshabilita). */
    val procesando: Set<String> = emptySet(),
    /** Resultado del último cambio, ej. "🔔 Aviso enviado a Ana (CMD-7K3F9Q)". */
    val mensaje: String? = null
)

class BaristaViewModel : ViewModel() {

    private val repository = OrderRepository()

    private val _estado = MutableStateFlow(BaristaUiState())
    val estado: StateFlow<BaristaUiState> = _estado.asStateFlow()

    /** [silencioso] = actualización automática: no muestra el indicador de carga. */
    fun cargar(sesion: String, silencioso: Boolean = false) {
        if (_estado.value.cargando) return
        if (!silencioso) _estado.update { it.copy(cargando = true, error = null) }
        viewModelScope.launch {
            try {
                val pedidos = repository.pendientesBarista(sesion)
                _estado.update { it.copy(cargando = false, pedidos = pedidos, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _estado.update { it.copy(cargando = false, error = e.message) }
            } catch (e: Exception) {
                _estado.update { it.copy(cargando = false, error = "Ocurrió un error inesperado.") }
            }
        }
    }

    /** "listo" manda el aviso al teléfono (y reloj) del cliente; "entregado" lo quita de la lista. */
    fun cambiarEstado(sesion: String, pedido: Order, estado: String) {
        if (pedido.folio in _estado.value.procesando) return
        _estado.update { it.copy(procesando = it.procesando + pedido.folio, mensaje = null) }
        viewModelScope.launch {
            val mensaje = try {
                val cambio = repository.cambiarEstado(sesion, pedido.folio, estado)
                _estado.update { actual ->
                    val pedidos = if (estado == "entregado") {
                        actual.pedidos.filterNot { it.folio == pedido.folio }
                    } else {
                        actual.pedidos.map { if (it.folio == pedido.folio) cambio.pedido.copy(avisable = it.avisable) else it }
                    }
                    actual.copy(pedidos = pedidos)
                }
                when {
                    estado == "entregado" -> "✅ ${pedido.folio} entregado"
                    cambio.notificacion == "enviada" -> "🔔 Aviso enviado a ${pedido.customer} (${pedido.folio})"
                    else -> "⚠️ ${pedido.folio} marcado listo, pero sin aviso: ${cambio.notificacion}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                "📡 ${e.message}"
            } catch (e: Exception) {
                "📡 Ocurrió un error inesperado."
            }
            _estado.update { it.copy(procesando = it.procesando - pedido.folio, mensaje = mensaje) }
        }
    }

    fun mensajeMostrado() {
        _estado.update { it.copy(mensaje = null) }
    }
}
