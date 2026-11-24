package com.example.cafeteria

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * PRÁCTICA 12 · Resiliencia: reintento con espera progresiva (exponential backoff) e idempotencia.
 * El servidor real se reemplaza por MockWebServer para provocar fallas controladas.
 * Las esperas se registran en una lista en vez de dormir (la prueba no tarda los segundos reales).
 */
class P12ResilienciaTest {

    private lateinit var servidor: MockWebServer
    private val esperas = mutableListOf<Long>()
    private val avisos = mutableListOf<String>()

    /** 3 intentos, base 500 ms, azar fijo 0.5 (factor 1.0) y sin dormir de verdad. */
    private val politica = PoliticaReintentos(intentos = 3, esperaBaseMs = 500, azar = { 0.5 }, dormir = { esperas.add(it) })
    private lateinit var repo: OrderRepository

    @Before fun preparar() {
        servidor = MockWebServer().apply { start() }
        repo = OrderRepository(baseUrl = servidor.url("/api/").toString(), reintentos = politica)
    }

    @After fun cerrar() = try { servidor.shutdown() } catch (_: Exception) {}

    private fun responder(codigo: Int, cuerpo: String) =
        servidor.enqueue(MockResponse().setResponseCode(codigo).setBody(cuerpo))

    private val carrito = listOf(CartItem(Product("1", "Café", "", 25.0, ""), 1))
    private val pedidoJson = """{"error":false,"pedido":{"id":9,"folio":"CMD-P12AAA","cliente":"Ana","telefono":"4420000012",
        "total":"25.00","notas":null,"latitud":null,"longitud":null,"direccion":null,"items":[]}}"""
    private val vacio = """{"error":false,"total":0,"pedidos":[]}"""

    private suspend fun crear(clave: String = "11111111-2222-3333-4444-555555555555") =
        repo.crearPedido("Ana", "4420000012", "", null, null, carrito, clave) { sig, total, _ -> avisos.add("$sig/$total") }

    // ---------- La política de espera ----------

    @Test fun espera_progresiva_se_duplica_y_tiene_tope() {
        val p = PoliticaReintentos(esperaBaseMs = 500, esperaMaximaMs = 4000, azar = { 0.5 })
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 4000L), (1..5).map { p.esperaDespuesDe(it) })
    }

    @Test fun azar_mueve_la_espera_entre_75_y_125_por_ciento() {
        assertEquals(375L, PoliticaReintentos(azar = { 0.0 }).esperaDespuesDe(1))
        assertEquals(625L, PoliticaReintentos(azar = { 1.0 }).esperaDespuesDe(1))
    }

    @Test fun sin_reintentos_hace_un_solo_intento() = runTest {
        var llamadas = 0
        try {
            PoliticaReintentos.SIN_REINTENTOS.ejecutar { llamadas++; throw ApiException("x", reintentable = true) }
        } catch (_: ApiException) {}
        assertEquals(1, llamadas)
    }

    // ---------- RES-04: HTTP 500/503 → reintento controlado ----------

    @Test fun res04_un_503_se_reintenta_y_luego_funciona() = runTest {
        responder(503, """{"error":true,"message":"El servidor está ocupado."}""")
        responder(201, pedidoJson)
        assertEquals("CMD-P12AAA", crear().folio)
        assertEquals(2, servidor.requestCount)
        assertEquals(listOf(500L), esperas)
        assertEquals(listOf("2/3"), avisos)          // la interfaz muestra "Reintentando (2 de 3)…"
    }

    @Test fun res04_tres_500_seguidos_se_reportan_con_mensaje_sin_romper() = runTest {
        repeat(3) { responder(500, """{"error":true,"message":"Error al procesar el pedido."}""") }
        try { crear(); fail() } catch (e: ApiException) {
            assertEquals("Error al procesar el pedido.", e.message)
            assertTrue(e.reintentable)
        }
        assertEquals(3, servidor.requestCount)        // no más de los 3 intentos
        assertEquals(listOf(500L, 1000L), esperas)    // 500 ms y 1 s entre intentos
    }

    // ---------- RES-05: 401 / 403 / 422 → no se reintenta ----------

    @Test fun res05_401_y_403_no_se_reintentan() = runTest {
        responder(401, """{"error":true,"message":"Tu sesión expiró. Vuelve a iniciar sesión."}""")
        try { repo.pendientesBarista("a".repeat(64)); fail() } catch (e: ApiException) {
            assertEquals("Tu sesión expiró. Vuelve a iniciar sesión.", e.message); assertFalse(e.reintentable)
        }
        responder(403, """{"error":true,"message":"Esta sección es solo para el barista."}""")
        try { repo.pendientesBarista("a".repeat(64)); fail() } catch (e: ApiException) {
            assertEquals("Esta sección es solo para el barista.", e.message); assertFalse(e.reintentable)
        }
        assertEquals(2, servidor.requestCount)
        assertTrue(esperas.isEmpty())
    }

    @Test fun validacion_422_no_se_reintenta() = runTest {
        responder(422, """{"error":true,"message":"El teléfono debe tener 10 dígitos."}""")
        try { crear(); fail() } catch (e: ApiException) { assertFalse(e.reintentable) }
        assertEquals(1, servidor.requestCount)
    }

    // ---------- RES-07: reintento de una escritura sin duplicar ----------

    @Test fun res07_todos_los_intentos_llevan_la_misma_clave_de_idempotencia() = runTest {
        responder(503, "{}"); responder(500, "{}"); responder(201, pedidoJson)
        crear("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val claves = (1..3).map { servidor.takeRequest().getHeader("Idempotency-Key") }
        assertEquals(List(3) { "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" }, claves)
    }

    /**
     * El caso de la práctica 11 (DEF-03): el servidor guarda el pedido pero la respuesta se pierde.
     * Ahora la app reintenta con la MISMA clave y el servidor contesta 200 con el pedido ya guardado
     * ("repetido": true) en vez de crear otro.
     */
    @Test fun res07_respuesta_perdida_y_reintento_devuelve_el_mismo_pedido() = runTest {
        servidor.enqueue(MockResponse().setBody(pedidoJson.repeat(30)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        responder(200, pedidoJson.replace("\"error\":false", "\"error\":false,\"repetido\":true"))
        val pedido = crear()
        assertEquals("CMD-P12AAA", pedido.folio)
        assertEquals(2, servidor.requestCount)
        val c1 = servidor.takeRequest().getHeader("Idempotency-Key")
        val c2 = servidor.takeRequest().getHeader("Idempotency-Key")
        assertEquals(c1, c2)
    }

    @Test fun cada_pedido_nuevo_lleva_una_clave_distinta_por_omision() = runTest {
        responder(201, pedidoJson); responder(201, pedidoJson)
        repo.crearPedido("Ana", "4420000012", "", null, null, carrito)
        repo.crearPedido("Ana", "4420000012", "", null, null, carrito)
        val c1 = servidor.takeRequest().getHeader("Idempotency-Key")!!
        val c2 = servidor.takeRequest().getHeader("Idempotency-Key")!!
        assertTrue(c1 != c2 && Regex("^[0-9a-f-]{36}$").matches(c1))
    }

    // ---------- RES-01 / RES-03 / RES-06: sin red, servidor callado, recuperación ----------

    @Test fun res01_sin_internet_se_reintenta_y_termina_en_error_controlado() = runTest {
        val sinRed = OrderRepository(baseUrl = "http://servidor-que-no-existe.invalid/api/", reintentos = politica)
        try { sinRed.consultarPorFolio("CMD-AAAAAA"); fail() } catch (e: ApiException) {
            assertEquals("Sin conexión a internet. Revisa tu red.", e.message)
        }
        assertEquals(listOf(500L, 1000L), esperas)  // detecta en ~1.5 s + 3 intentos fallidos inmediatos
    }

    @Test fun res03_servidor_sin_respuesta_timeout_y_luego_recupera() = runTest {
        // 1.er intento: el servidor recibe y no contesta (timeout 8 s); 2.º intento: responde
        servidor.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        responder(200, vacio)
        val t0 = System.currentTimeMillis()
        assertTrue(repo.consultarPorFolio("CMD-AAAAAA").isEmpty())
        val ms = System.currentTimeMillis() - t0
        assertTrue("tardó $ms ms", ms >= ApiConfig.TIMEOUT_MS)
        assertEquals(2, servidor.requestCount)
    }

    @Test fun res06_recuperacion_despues_de_corte_a_media_respuesta() = runTest {
        servidor.enqueue(MockResponse().setBody(vacio.repeat(40)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        responder(200, vacio)
        assertTrue(repo.consultarPedidos("4420000012").isEmpty())
        assertEquals(2, servidor.requestCount)
    }

    // ---------- Cambio de estado del barista: no idempotente, sin reintento ----------

    @Test fun cambiar_estado_no_se_reintenta_automaticamente() = runTest {
        responder(503, """{"error":true,"message":"El servidor está ocupado. Intenta de nuevo en un momento."}""")
        try { repo.cambiarEstado("a".repeat(64), "CMD-AAAAAA", "listo"); fail() } catch (e: ApiException) {
            assertTrue(e.reintentable)
        }
        assertEquals(1, servidor.requestCount)
    }
}
