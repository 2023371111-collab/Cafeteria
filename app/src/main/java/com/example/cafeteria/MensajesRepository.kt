package com.example.cafeteria

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
// Práctica 6 (MobileConnect): avisos entre el cliente (Dispositivo A) y el barista (Dispositivo B).
// El canal de comunicación es backend/api/mensajes.php: un dispositivo hace POST y el otro
// lo recibe con GET (consulta cada 3 s, ver ChatViewModel).
// =============================================

/** Quién manda el aviso. Los dos "dispositivos" de la práctica. */
enum class Emisor(val api: String, val etiqueta: String, val icono: String) {
    CLIENTE("cliente", "Cliente", "📱"),
    BARISTA("barista", "Barista", "☕");

    companion object {
        fun deApi(valor: String) = if (valor == BARISTA.api) BARISTA else CLIENTE
    }
}

data class Mensaje(
    val id: Int,
    val emisor: Emisor,
    val texto: String,
    /** "2026-11-05 18:04:12" tal como lo guarda MySQL (hora de envío). */
    val creadoEn: String,
    /** Cajón en que se atendió; null si llegó mientras el pedido estaba en espera. */
    val cajon: String?
)

/** Estado de la conversación de un pedido que regresa el servidor. */
data class Conversacion(
    val folio: String,
    val cliente: String,
    val estadoPedido: String,
    /** Letra del cajón que ocupa el pedido, o null. */
    val cajon: String?,
    /** Lugar en la lista de espera (1 = el siguiente), o null si no espera. */
    val posicionEspera: Int?,
    val mensajes: List<Mensaje>
)

data class Cajon(
    val letra: String,
    val libre: Boolean,
    val folio: String?,
    val cliente: String?,
    val ocupadoDesde: String?,
    /** Minutos que le quedan antes de liberarse solo por inactividad. */
    val minutosRestantes: Int,
    val totalMensajes: Int,
    val ultimoMensaje: String?
)

data class EnEspera(val folio: String, val cliente: String, val desde: String)

data class PilaCajones(val cajones: List<Cajon>, val espera: List<EnEspera>)

/** Cliente HTTP de mensajes.php. [baseUrl] se cambia en las pruebas. */
class MensajesRepository(private val baseUrl: String = ApiConfig.BASE_URL) {

    /** Conversación del pedido. El cliente manda [telefono]; el barista manda [sesion]. */
    suspend fun conversacion(folio: String, telefono: String?, sesion: String?, desde: Int = 0): Conversacion =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append(baseUrl).append("mensajes.php?folio=").append(codificar(folio))
                if (telefono != null) append("&telefono=").append(codificar(telefono))
                if (desde > 0) append("&desde=").append(desde)
            }
            parseConversacion(peticion("GET", url, null, sesion))
        }

    /** Envía un aviso; el servidor regresa la conversación actualizada (con el cajón asignado). */
    suspend fun enviar(folio: String, telefono: String?, sesion: String?, emisor: Emisor, texto: String): Conversacion =
        withContext(Dispatchers.IO) {
            val cuerpo = JSONObject()
                .put("folio", folio)
                .put("emisor", emisor.api)
                .put("texto", texto)
            telefono?.let { cuerpo.put("telefono", it) }
            parseConversacion(peticion("POST", baseUrl + "mensajes.php", cuerpo.toString(), sesion))
        }

    /** Solo barista: la pila de cajones (ocupados arriba, libres abajo) y quién espera. */
    suspend fun cajones(sesion: String): PilaCajones = withContext(Dispatchers.IO) {
        parsePila(peticion("GET", baseUrl + "mensajes.php?cajones=1", null, sesion))
    }

    /** Solo barista: vacía el cajón; el primero en espera lo toma. */
    suspend fun liberar(sesion: String, letra: String): PilaCajones = withContext(Dispatchers.IO) {
        val cuerpo = JSONObject().put("accion", "liberar").put("letra", letra)
        parsePila(peticion("POST", baseUrl + "mensajes.php", cuerpo.toString(), sesion))
    }

    private fun codificar(valor: String) = URLEncoder.encode(valor, "UTF-8")

    private fun peticion(metodo: String, url: String, cuerpo: String?, sesion: String?): JSONObject {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = metodo
                connectTimeout = ApiConfig.TIMEOUT_MS
                readTimeout = ApiConfig.TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
                sesion?.let { setRequestProperty("X-Sesion", it) }
            }
            if (cuerpo != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw ApiException(mensajeDelServidor(body) ?: "Error del servidor ($code). Intenta más tarde.")
            }
            val json = JSONObject(body)
            if (json.optBoolean("error", false)) {
                throw ApiException(json.optString("message", "No se pudo completar la operación."))
            }
            return json
        } catch (e: ApiException) {
            throw e
        } catch (e: UnknownHostException) {
            throw ApiException("Sin conexión a internet. Tu aviso no se envió.", e)
        } catch (e: ConnectException) {
            throw ApiException("No se pudo conectar con el servidor. Tu aviso no se envió.", e)
        } catch (e: SocketTimeoutException) {
            throw ApiException("El servidor tardó demasiado en responder.", e)
        } catch (e: JSONException) {
            throw ApiException("La respuesta del servidor no es válida.", e)
        } catch (e: IOException) {
            throw ApiException("Error de red. Tu aviso no se envió.", e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun mensajeDelServidor(body: String): String? = try {
        JSONObject(body).optString("message").takeIf { it.isNotBlank() }
    } catch (e: JSONException) {
        null
    }

    private fun JSONObject.textoONulo(campo: String): String? = if (isNull(campo)) null else getString(campo)

    private fun parseConversacion(json: JSONObject): Conversacion {
        val pedido = json.getJSONObject("pedido")
        val array = json.optJSONArray("mensajes") ?: JSONArray()
        return Conversacion(
            folio = pedido.getString("folio"),
            cliente = pedido.getString("cliente"),
            estadoPedido = pedido.getString("estado"),
            cajon = json.textoONulo("cajon"),
            posicionEspera = if (json.isNull("espera")) null else json.getInt("espera"),
            mensajes = List(array.length()) { i ->
                val m = array.getJSONObject(i)
                Mensaje(
                    id = m.getInt("id"),
                    emisor = Emisor.deApi(m.getString("emisor")),
                    texto = m.getString("texto"),
                    creadoEn = m.getString("creado_en"),
                    cajon = m.textoONulo("cajon")
                )
            }
        )
    }

    private fun parsePila(json: JSONObject): PilaCajones {
        val cajones = json.getJSONArray("cajones")
        val espera = json.optJSONArray("espera") ?: JSONArray()
        return PilaCajones(
            cajones = List(cajones.length()) { i ->
                val c = cajones.getJSONObject(i)
                Cajon(
                    letra = c.getString("letra"),
                    libre = c.optBoolean("libre", c.isNull("folio")),
                    folio = c.textoONulo("folio"),
                    cliente = c.textoONulo("cliente"),
                    ocupadoDesde = c.textoONulo("ocupado_desde"),
                    minutosRestantes = c.optInt("minutos_restantes", 0),
                    totalMensajes = c.optInt("total_mensajes", 0),
                    ultimoMensaje = c.textoONulo("ultimo_mensaje")
                )
            },
            espera = List(espera.length()) { i ->
                val e = espera.getJSONObject(i)
                EnEspera(e.getString("folio"), e.getString("cliente"), e.getString("desde"))
            }
        )
    }
}
