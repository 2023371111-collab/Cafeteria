package com.example.cafeteria

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** Respuesta ya procesada para mostrarla en pantalla. */
data class RespuestaWeb(
    val codigo: Int,
    val milisegundos: Long,
    val bytes: Int,
    /** Pares campo → valor si la respuesta es un objeto JSON; vacío si es texto. */
    val campos: List<Pair<String, String>>,
    val cuerpo: String
)

/** Estados de la pantalla "Consulta de información" (práctica 3). */
sealed interface ConsultaUiState {
    data object Inicio : ConsultaUiState
    data object Consultando : ConsultaUiState
    data class Exito(val respuesta: RespuestaWeb) : ConsultaUiState
    /** [detalle] explica la causa técnica debajo del mensaje general. */
    data class Error(val detalle: String) : ConsultaUiState
}

class ConsultaWebViewModel : ViewModel() {

    private val _url = MutableStateFlow(URL_PREASIGNADA)
    val url: StateFlow<String> = _url.asStateFlow()

    private val _estado = MutableStateFlow<ConsultaUiState>(ConsultaUiState.Inicio)
    val estado: StateFlow<ConsultaUiState> = _estado.asStateFlow()

    /** Prueba 3: permite cambiar la URL sin recompilar. */
    fun onUrlChange(valor: String) = _url.update { valor.trim() }

    fun restaurarUrl() = _url.update { URL_PREASIGNADA }

    /** Botón CONSULTAR: petición GET a la URL y procesamiento de la respuesta. */
    fun consultar() {
        if (_estado.value is ConsultaUiState.Consultando) return
        _estado.value = ConsultaUiState.Consultando
        viewModelScope.launch {
            _estado.value = try {
                ConsultaUiState.Exito(peticionGet(_url.value))
            } catch (e: UnknownHostException) {
                ConsultaUiState.Error("Sin conexión a internet: no se encontró el servidor")
            } catch (e: SocketTimeoutException) {
                ConsultaUiState.Error("El servidor tardó demasiado en responder")
            } catch (e: IOException) {
                ConsultaUiState.Error(e.localizedMessage ?: "Sin conexión")
            } catch (e: IllegalArgumentException) {
                ConsultaUiState.Error("La URL no es válida")
            }
        }
    }

    private suspend fun peticionGet(direccion: String): RespuestaWeb = withContext(Dispatchers.IO) {
        val inicio = System.currentTimeMillis()
        val conexion = (URL(direccion).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = ApiConfig.TIMEOUT_MS
            readTimeout = ApiConfig.TIMEOUT_MS
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
        try {
            val codigo = conexion.responseCode
            if (codigo !in 200..299) throw IOException("El servidor respondió $codigo")
            val cuerpo = conexion.inputStream.bufferedReader().use { it.readText() }
            RespuestaWeb(
                codigo = codigo,
                milisegundos = System.currentTimeMillis() - inicio,
                bytes = cuerpo.toByteArray().size,
                campos = procesarJson(cuerpo),
                cuerpo = cuerpo
            )
        } finally {
            conexion.disconnect()
        }
    }

    /** Si la respuesta es un objeto JSON lo convierte en una lista legible; si no, regresa vacío. */
    private fun procesarJson(cuerpo: String): List<Pair<String, String>> = try {
        val json = JSONObject(cuerpo)
        json.keys().asSequence().map { clave ->
            val valor = when (val v = json.get(clave)) {
                JSONObject.NULL -> "—"
                is JSONArray -> "${v.length()} elemento(s)"
                is JSONObject -> "{ ${v.length()} campo(s) }"
                else -> v.toString()
            }
            clave to valor
        }.toList()
    } catch (e: JSONException) {
        emptyList()
    }

    companion object {
        /** URL preasignada: recurso de nuestro propio servidor. */
        const val URL_PREASIGNADA = "https://devnationqro.com/cafeteria-api/api/info.php"
    }
}
