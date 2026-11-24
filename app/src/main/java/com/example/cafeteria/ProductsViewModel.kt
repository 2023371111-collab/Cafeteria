package com.example.cafeteria

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Estados posibles de la pantalla del menú. */
sealed interface ProductsUiState {
    data object Loading : ProductsUiState
    data class Success(val products: List<Product>) : ProductsUiState
    data class Empty(val query: String) : ProductsUiState
    data class Error(val message: String) : ProductsUiState
}

class ProductsViewModel(
    private val repository: ProductRepository = ProductRepository()
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState = MutableStateFlow<ProductsUiState>(ProductsUiState.Loading)
    val uiState: StateFlow<ProductsUiState> = _uiState.asStateFlow()

    // Cada incremento fuerza una nueva petición con la misma búsqueda (botón "Reintentar")
    private val retryTrigger = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            val busqueda = _query
                .debounce(300)                 // espera a que el usuario deje de escribir
                .map { it.trim() }
                .distinctUntilChanged()        // no repite la petición si el texto no cambió

            combine(busqueda, retryTrigger) { q, _ -> q }
                .collectLatest { q -> cargar(q) } // cancela la búsqueda anterior si llega una nueva
        }
    }

    fun onQueryChange(nuevo: String) {
        _query.value = nuevo
    }

    fun retry() {
        retryTrigger.value++
    }

    private suspend fun cargar(q: String) {
        _uiState.value = ProductsUiState.Loading
        _uiState.value = try {
            val productos = Perf.medir(if (q.isEmpty()) "cargarMenu" else "buscarProductos") { repository.buscarProductos(q) }
            if (productos.isEmpty()) ProductsUiState.Empty(q) else ProductsUiState.Success(productos)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            ProductsUiState.Error(e.message ?: "Error desconocido")
        } catch (e: Exception) {
            ProductsUiState.Error("Ocurrió un error inesperado.")
        }
    }
}
