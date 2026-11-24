package com.example.cafeteria

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * PRÁCTICA 11 · WHITE BOX · Método 3 (comunicación): OrderRepository.peticion()
 * Se ejecuta por medio de consultarPorFolio (GET, sin sesión), crearPedido (POST con cuerpo)
 * y pendientesBarista (GET con encabezado X-Sesion).
 *
 * Decisiones:
 *   D1  sesion?.let            (manda o no X-Sesion)
 *   D2  cuerpo != null         (POST con cuerpo / GET)
 *   D3  code in 200..299       (inputStream / errorStream)
 *   D4  ?.bufferedReader       (errorStream null → cuerpo vacío)
 *   D5  code !in 200..299      → ApiException con mensaje del servidor o genérico (D5b: ?: )
 *   D6  json.optBoolean("error")
 *   catch: ApiException, UnknownHost, Connect, SocketTimeout, JSONException, IOException (6)
 *   finally: connection?.disconnect()
 * V(G) = 7 decisiones + 6 catch + 1 = 14
 */
class WB03PeticionTest {

    private lateinit var servidor: MockWebServer
    private lateinit var repo: OrderRepository

    @Before fun preparar() {
        servidor = MockWebServer().apply { start() }
        repo = OrderRepository(baseUrl = servidor.url("/api/").toString(), reintentos = PoliticaReintentos.SIN_REINTENTOS)
    }

    @After fun cerrar() = try { servidor.shutdown() } catch (_: Exception) {}

    private fun responder(codigo: Int, cuerpo: String) =
        servidor.enqueue(MockResponse().setResponseCode(codigo).setBody(cuerpo))

    private suspend fun mensajeDeError(bloque: suspend () -> Unit): String {
        try { bloque() } catch (e: ApiException) { return e.message!! }
        fail("Se esperaba ApiException"); return ""
    }

    /** Busca el tipo en toda la cadena de causas (las corrutinas envuelven la excepción al recuperar la pila). */
    private fun Throwable.tieneCausa(tipo: Class<*>): Boolean =
        generateSequence(this) { it.cause }.any { tipo.isInstance(it) }

    private val vacio = """{"error":false,"total":0,"pedidos":[]}"""

    // ---------- Caminos exitosos ----------

    /** D1=F, D2=F, D3=V, D6=F: GET sin sesión, 200 con JSON. */
    @Test fun c1_get_200_sin_sesion() = runTest {
        responder(200, vacio)
        assertTrue(repo.consultarPorFolio("CMD-7K3F9Q").isEmpty())
        val req = servidor.takeRequest()
        assertEquals("GET", req.method)
        assertNull(req.getHeader("X-Sesion"))
        assertEquals("application/json", req.getHeader("Accept"))
    }

    /** D1=V: el token viaja en el encabezado X-Sesion, nunca en la URL. */
    @Test fun c2_get_con_sesion_manda_encabezado() = runTest {
        responder(200, vacio)
        repo.pendientesBarista("a".repeat(64))
        val req = servidor.takeRequest()
        assertEquals("a".repeat(64), req.getHeader("X-Sesion"))
        assertTrue(!req.path!!.contains("aaaa"))
    }

    /** D2=V: POST con cuerpo JSON en UTF-8. */
    @Test fun c3_post_con_cuerpo() = runTest {
        responder(201, """{"error":false,"pedido":{"id":1,"cliente":"Ana","telefono":"4421234567","total":"35.00","notas":null,"latitud":null,"longitud":null,"direccion":null}}""")
        repo.crearPedido("Ana", "4421234567", "", null, null,
            listOf(CartItem(Product("1", "Café", "", 35.0, ""), 1)))
        val req = servidor.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertTrue(req.body.readUtf8().contains("\"producto_id\":1"))
    }

    // ---------- Códigos HTTP de error (D3=F, D5) ----------

    /** D5: 4xx con mensaje JSON → se muestra el del servidor. */
    @Test fun c4_422_mensaje_del_servidor() = runTest {
        responder(422, """{"error":true,"message":"El folio debe ser como CMD-7K3F9Q."}""")
        assertEquals("El folio debe ser como CMD-7K3F9Q.", mensajeDeError { repo.consultarPorFolio("x") })
    }

    /** D5b: 500 con HTML (sin JSON) → mensaje genérico con el código. */
    @Test fun c5_500_sin_json_mensaje_generico() = runTest {
        responder(500, "<html>Internal Server Error</html>")
        assertEquals("Error del servidor (500). Intenta más tarde.", mensajeDeError { repo.consultarPorFolio("x") })
    }

    /** D4: 401 con cuerpo vacío. */
    @Test fun c6_401_cuerpo_vacio() = runTest {
        responder(401, "")
        assertEquals("Error del servidor (401). Intenta más tarde.", mensajeDeError { repo.pendientesBarista("t") })
    }

    /** Valores límite del rango 2xx: 299 es éxito, 300 ya no. */
    @Test fun limite_codigos_299_y_300() = runTest {
        responder(299, vacio)
        assertTrue(repo.consultarPorFolio("x").isEmpty())
        responder(300, "")
        assertEquals("Error del servidor (300). Intenta más tarde.", mensajeDeError { repo.consultarPorFolio("x") })
        responder(199, "")
        assertTrue(mensajeDeError { repo.consultarPorFolio("x") }.isNotBlank())
    }

    /** D6=V: código 200 pero {"error":true} → no se trata como éxito. */
    @Test fun c7_200_con_error_true() = runTest {
        responder(200, """{"error":true,"message":"No se pudo."}""")
        assertEquals("No se pudo.", mensajeDeError { repo.consultarPorFolio("x") })
    }

    // ---------- Excepciones (cada catch) ----------

    /** catch JSONException: 200 con HTML (p. ej. página de error del hosting). */
    @Test fun e1_respuesta_html_con_200() = runTest {
        responder(200, "<html>Mantenimiento</html>")
        assertEquals("La respuesta del servidor no es válida.", mensajeDeError { repo.consultarPorFolio("x") })
    }

    /** catch UnknownHostException: sin DNS / sin internet. */
    @Test fun e2_host_inexistente() = runTest {
        val sinRed = OrderRepository(baseUrl = "http://servidor-que-no-existe.invalid/api/", reintentos = PoliticaReintentos.SIN_REINTENTOS)
        try { sinRed.consultarPorFolio("x"); fail() } catch (e: ApiException) {
            assertEquals("Sin conexión a internet. Revisa tu red.", e.message)
            assertTrue(e.tieneCausa(UnknownHostException::class.java))
        }
    }

    /** catch ConnectException: el servidor está apagado (puerto cerrado). */
    @Test fun e3_servidor_apagado() = runTest {
        val url = servidor.url("/api/").toString()
        servidor.shutdown()
        try { OrderRepository(baseUrl = url, reintentos = PoliticaReintentos.SIN_REINTENTOS).consultarPorFolio("x"); fail() } catch (e: ApiException) {
            assertEquals("No se pudo conectar con el servidor.", e.message)
            assertTrue(e.tieneCausa(ConnectException::class.java))
        }
    }

    /** catch SocketTimeoutException: el servidor no contesta en 8 s (ApiConfig.TIMEOUT_MS). */
    @Test fun e4_timeout() = runTest {
        servidor.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val inicio = System.currentTimeMillis()
        try { repo.consultarPorFolio("x"); fail() } catch (e: ApiException) {
            assertEquals("El servidor tardó demasiado en responder.", e.message)
            assertTrue(e.tieneCausa(SocketTimeoutException::class.java))
        }
        val ms = System.currentTimeMillis() - inicio
        assertTrue("tardó $ms ms", ms in ApiConfig.TIMEOUT_MS.toLong()..(ApiConfig.TIMEOUT_MS + 3000L))
    }

    /** catch IOException: el servidor cierra la conexión a la mitad del cuerpo (pérdida de conectividad). */
    @Test fun e5_conexion_cortada_a_media_respuesta() = runTest {
        servidor.enqueue(MockResponse().setBody(vacio.repeat(50))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val msg = mensajeDeError { repo.consultarPorFolio("x") }
        assertTrue(msg, msg.startsWith("Error de red") || msg == "La respuesta del servidor no es válida.")
    }

    /**
     * OBSERVACIÓN NET-05: si el servidor recibe la petición y se calla sin cerrar el socket,
     * la app no lo detecta hasta que vence el timeout de lectura (8 s) y lo reporta como lentitud.
     */
    @Test fun e6_servidor_recibe_y_no_contesta_se_detecta_por_timeout() = runTest {
        servidor.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals("El servidor tardó demasiado en responder.", mensajeDeError { repo.consultarPorFolio("x") })
    }

    /**
     * catch IOException con connection == null en el finally: la URL está mal formada
     * (MalformedURLException) y la conexión nunca se llega a abrir.
     */
    @Test fun e7_url_mal_formada_no_abre_conexion() = runTest {
        val mal = OrderRepository(baseUrl = "sin-protocolo/api/", reintentos = PoliticaReintentos.SIN_REINTENTOS)
        val msg = mensajeDeError { mal.consultarPorFolio("x") }
        assertTrue(msg, msg.startsWith("Error de red"))
    }

    /**
     * Práctica 11: DEF-08, el teléfono iba sin codificar y un "&" agregaba parámetros.
     * Práctica 12 (corregido): el valor viaja codificado como un solo parámetro.
     */
    @Test fun parametro_se_codifica() = runTest {
        responder(200, vacio)
        repo.consultarPedidos("4421234567&folio=CMD-AAAAAA")
        val path = servidor.takeRequest().path!!
        assertTrue(path, path.endsWith("telefono=4421234567%26folio%3DCMD-AAAAAA"))
        assertFalse(path, path.contains("&folio="))
    }
}
