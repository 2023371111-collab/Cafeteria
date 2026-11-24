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
import java.util.UUID

data class OrderItem(
    val productId: Int,
    val name: String,
    val unitPrice: Double,
    val quantity: Int
)

data class Order(
    val id: Int,
    /** Folio alfanumérico de la comanda (ej. CMD-7K3F9Q); lo genera el servidor y no se repite. */
    val folio: String,
    val customer: String,
    val phone: String,
    val notes: String,
    val total: Double,
    val status: String,
    val createdAt: String,
    val items: List<OrderItem>,
    /** Ubicación de entrega; null si el pedido se recoge en la cafetería. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val deliveryAddress: String? = null,
    /** Solo en la vista del barista: si el cliente activó el aviso de "pedido listo". */
    val avisable: Boolean = false
)

/** Respuesta de barista.php al cambiar un estado. [notificacion]: "enviada", el motivo de falla o null. */
data class CambioEstado(val pedido: Order, val notificacion: String?)

/**
 * Envía y consulta pedidos en la nube (backend/api/pedidos.php).
 * [baseUrl] se puede cambiar en las pruebas para apuntar a un servidor falso o de pruebas.
 */
class OrderRepository(
    private val baseUrl: String = ApiConfig.BASE_URL,
    /** Práctica 12: reintento con espera progresiva para lecturas y escrituras idempotentes. */
    private val reintentos: PoliticaReintentos = PoliticaReintentos()
) {

    /**
     * POST pedidos.php con el pedido en JSON. Solo se mandan ids y cantidades:
     * el servidor pone los precios desde la base de datos y calcula el total.
     *
     * Práctica 12: [claveIdempotencia] viaja en el encabezado Idempotency-Key y es LA MISMA en
     * todos los reintentos. Si el servidor ya guardó el pedido (la respuesta se perdió), devuelve
     * ese mismo pedido en vez de crear otro, por eso aquí sí es seguro reintentar una escritura.
     */
    suspend fun crearPedido(
        cliente: String,
        telefono: String,
        notas: String,
        ubicacion: Ubicacion?,
        fcmToken: String?,
        carrito: List<CartItem>,
        claveIdempotencia: String = UUID.randomUUID().toString(),
        alReintentar: (siguiente: Int, total: Int, esperaMs: Long) -> Unit = { _, _, _ -> }
    ): Order = withContext(Dispatchers.IO) {
        val items = JSONArray()
        carrito.forEach { item ->
            val productoId = item.product.id.toIntOrNull()
                ?: throw ApiException("El producto \"${item.product.name}\" no es válido. Actualiza el menú.")
            items.put(JSONObject().apply {
                put("producto_id", productoId)
                put("cantidad", item.quantity)
            })
        }
        val cuerpo = JSONObject().apply {
            put("cliente", cliente)
            put("telefono", telefono)
            put("notas", notas)
            if (ubicacion != null) {
                put("latitud", ubicacion.latitud)
                put("longitud", ubicacion.longitud)
                ubicacion.direccion?.let { put("direccion", it) }
            }
            fcmToken?.let { put("fcm_token", it) }
            put("items", items)
        }

        val encabezados = mapOf("Idempotency-Key" to claveIdempotencia)
        reintentos.ejecutar(alReintentar) {
            val respuesta = peticion("POST", baseUrl + "pedidos.php", cuerpo.toString(), encabezados = encabezados)
            leer { parsePedido(respuesta.getJSONObject("pedido")) }
        }
    }

    /** GET pedidos.php?telefono=... : los pedidos más recientes de ese teléfono. */
    suspend fun consultarPedidos(telefono: String): List<Order> =
        consultarLista(baseUrl + "pedidos.php?telefono=" + codificar(telefono))

    /** GET pedidos.php?folio=CMD-XXXXXX : la comanda con ese folio (lista vacía si no existe). */
    suspend fun consultarPorFolio(folio: String): List<Order> =
        consultarLista(baseUrl + "pedidos.php?folio=" + codificar(folio))

    // ---------- Barista (barista.php, requiere el token de sesión) ----------

    /** Pedidos recibidos y listos, el más viejo primero. */
    suspend fun pendientesBarista(sesion: String): List<Order> = consultarLista(baseUrl + "barista.php", sesion)

    /**
     * "listo" avisa al cliente por FCM; "entregado" lo saca de la lista.
     * SIN reintento automático: no es idempotente (un reintento tras perder la respuesta
     * recibiría 409 aunque el cambio sí se hizo); el barista actualiza la lista y decide.
     */
    suspend fun cambiarEstado(sesion: String, folio: String, estado: String): CambioEstado = withContext(Dispatchers.IO) {
        val cuerpo = JSONObject().put("folio", folio).put("estado", estado)
        val respuesta = peticion("POST", baseUrl + "barista.php", cuerpo.toString(), sesion)
        leer {
            CambioEstado(
                pedido = parsePedido(respuesta.getJSONObject("pedido")),
                notificacion = if (respuesta.isNull("notificacion")) null else respuesta.getString("notificacion")
            )
        }
    }

    /** GET que devuelve {"pedidos": [...]}. Una lectura se puede repetir: usa la política de reintentos. */
    private suspend fun consultarLista(url: String, sesion: String? = null): List<Order> =
        reintentos.ejecutar {
            withContext(Dispatchers.IO) {
                val respuesta = peticion("GET", url, null, sesion)
                leer {
                    val array = respuesta.getJSONArray("pedidos")
                    List(array.length()) { i -> parsePedido(array.getJSONObject(i)) }
                }
            }
        }

    /** Corrige DEF-08 (práctica 11): los valores van codificados, un "&" ya no agrega parámetros. */
    private fun codificar(valor: String): String = URLEncoder.encode(valor, "UTF-8")

    /** Corrige DEF-07 (práctica 11): un JSON incompleto se reporta como ApiException, no como JSONException. */
    private inline fun <T> leer(bloque: () -> T): T = try {
        bloque()
    } catch (e: JSONException) {
        throw ApiException("La respuesta del servidor no es válida.", e)
    }

    /** Hace la petición HTTP y traduce cualquier falla a un ApiException con mensaje claro. */
    private fun peticion(
        metodo: String,
        url: String,
        cuerpo: String?,
        sesion: String? = null,
        encabezados: Map<String, String> = emptyMap()
    ): JSONObject {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = metodo
                connectTimeout = ApiConfig.TIMEOUT_MS
                readTimeout = ApiConfig.TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                sesion?.let { setRequestProperty("X-Sesion", it) }
                encabezados.forEach { (k, v) -> setRequestProperty(k, v) }
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
                throw ApiException(mensajeDelServidor(body) ?: "Error del servidor ($code). Intenta más tarde.",
                    reintentable = codigoReintentable(code))
            }

            val json = JSONObject(body) // si el servidor manda HTML en vez de JSON, cae en JSONException
            if (json.optBoolean("error", false)) {
                throw ApiException(json.optString("message", "No se pudo completar la operación."))
            }
            return json
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

    private fun parsePedido(obj: JSONObject): Order {
        val items = obj.optJSONArray("items") ?: JSONArray()
        return Order(
            id = obj.getInt("id"),
            folio = obj.optString("folio", ""),
            customer = obj.getString("cliente"),
            phone = obj.getString("telefono"),
            notes = if (obj.isNull("notas")) "" else obj.getString("notas"),
            total = obj.getDouble("total"), // MySQL DECIMAL llega como texto; getDouble lo convierte
            status = obj.optString("estado", "recibido"),
            createdAt = obj.optString("creado_en", ""),
            latitude = if (obj.isNull("latitud")) null else obj.getDouble("latitud"),
            longitude = if (obj.isNull("longitud")) null else obj.getDouble("longitud"),
            deliveryAddress = if (obj.isNull("direccion")) null else obj.getString("direccion"),
            avisable = obj.optInt("avisable", 0) == 1,
            items = List(items.length()) { i ->
                val renglon = items.getJSONObject(i)
                OrderItem(
                    productId = renglon.getInt("producto_id"),
                    name = renglon.getString("nombre"),
                    unitPrice = renglon.getDouble("precio_unitario"),
                    quantity = renglon.getInt("cantidad")
                )
            }
        )
    }

    private fun mensajeDelServidor(body: String): String? = try {
        JSONObject(body).optString("message").takeIf { it.isNotBlank() }
    } catch (e: JSONException) {
        null
    }
}
