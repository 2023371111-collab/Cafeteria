package com.example.cafeteria

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

// =============================================
// Configuración de la API
// La URL sale de app/build.gradle.kts (API_URL). Para el servidor de pruebas:
//   gradlew ... -PapiUrl=http://10.0.2.2:8080/api/
// =============================================
object ApiConfig {
    const val BASE_URL = BuildConfig.API_URL
    const val TIMEOUT_MS = 8000
}

/**
 * Error de red con un mensaje listo para mostrarse al usuario.
 * [reintentable]: la falla es temporal (sin red, timeout, servidor ocupado 5xx) y la
 * operación se puede repetir; los rechazos 4xx (datos inválidos, sin permiso) no.
 */
class ApiException(
    message: String,
    cause: Throwable? = null,
    val reintentable: Boolean = false
) : Exception(message, cause)

/** Códigos HTTP que indican una falla temporal del servidor (se puede reintentar). */
fun codigoReintentable(code: Int) = code == 408 || code == 429 || code in 500..599

class ProductRepository(
    // Una lectura se puede repetir sin riesgo: reintento con espera progresiva (práctica 12)
    private val reintentos: PoliticaReintentos = PoliticaReintentos()
) {

    /**
     * Consulta GET productos.php (con ?q= si hay búsqueda).
     * Se ejecuta en Dispatchers.IO para no bloquear la interfaz.
     */
    suspend fun buscarProductos(query: String): List<Product> = reintentos.ejecutar { buscarUnaVez(query) }

    private suspend fun buscarUnaVez(query: String): List<Product> = withContext(Dispatchers.IO) {
        val url = buildString {
            append(ApiConfig.BASE_URL).append("productos.php")
            if (query.isNotBlank()) {
                append("?q=").append(URLEncoder.encode(query, "UTF-8"))
            }
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = ApiConfig.TIMEOUT_MS
                readTimeout = ApiConfig.TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }

            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                throw ApiException(mensajeDelServidor(body) ?: "Error del servidor ($code). Intenta más tarde.",
                    reintentable = codigoReintentable(code))
            }

            parseProductos(body)
        } catch (e: ApiException) {
            throw e
        } catch (e: UnknownHostException) {
            throw ApiException("Sin conexión a internet. Revisa tu red.", e, reintentable = true)
        } catch (e: ConnectException) {
            throw ApiException("No se pudo conectar con el servidor.", e, reintentable = true)
        } catch (e: SocketTimeoutException) {
            throw ApiException("El servidor tardó demasiado en responder.", e, reintentable = true)
        } catch (e: JSONException) {
            throw ApiException("La respuesta del servidor no es válida.", e)
        } catch (e: IOException) {
            throw ApiException("Error de red: ${e.localizedMessage ?: "desconocido"}", e, reintentable = true)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseProductos(body: String): List<Product> {
        val json = JSONObject(body)
        if (json.optBoolean("error", false)) {
            throw ApiException(json.optString("message", "Error al obtener los productos."))
        }
        val array = json.getJSONArray("productos")
        return List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            Product(
                id = obj.get("id").toString(),
                name = obj.getString("nombre"),
                description = if (obj.isNull("descripcion")) "" else obj.getString("descripcion"),
                price = obj.getDouble("precio"), // MySQL DECIMAL llega como texto; getDouble lo convierte
                imageUrl = if (obj.isNull("imagen_url")) "" else obj.getString("imagen_url")
            )
        }
    }

    private fun mensajeDelServidor(body: String): String? = try {
        JSONObject(body).optString("message").takeIf { it.isNotBlank() }
    } catch (e: JSONException) {
        null
    }
}
