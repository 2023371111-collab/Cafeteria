package com.example.cafeteria

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Pruebas UNITARIAS de OrderRepository (envío de pedidos).
 * La API real se reemplaza por MockWebServer (doble de prueba): no se usa internet
 * ni la base de datos, solo se revisa qué manda el repositorio y cómo interpreta la respuesta.
 */
class OrderRepositoryTest {

    private lateinit var servidor: MockWebServer
    private lateinit var repo: OrderRepository

    private val americano = Product("1", "Americano", "12 oz", 35.0, "")
    private val pan = Product("3", "Pan de elote", "", 27.9, "")

    @Before
    fun preparar() {
        servidor = MockWebServer()
        servidor.start()
        repo = OrderRepository(baseUrl = servidor.url("/api/").toString(), reintentos = PoliticaReintentos.SIN_REINTENTOS)
    }

    @After
    fun cerrar() {
        servidor.shutdown()
    }

    private fun responder(codigo: Int, cuerpo: String) {
        servidor.enqueue(MockResponse().setResponseCode(codigo).setBody(cuerpo))
    }

    private val pedidoGuardado = """
        {"error": false, "pedido": {
            "id": 7, "folio": "CMD-7K3F9Q", "cliente": "Ana López", "telefono": "4421234567",
            "notas": "sin azúcar", "total": "97.90", "estado": "recibido",
            "creado_en": "2026-10-26 10:15:00", "latitud": null, "longitud": null, "direccion": null,
            "items": [
                {"producto_id": 1, "nombre": "Americano", "precio_unitario": "35.00", "cantidad": 2},
                {"producto_id": 3, "nombre": "Pan de elote", "precio_unitario": "27.90", "cantidad": 1}
            ]}}
    """.trimIndent()

    // ---------- Caso válido ----------

    @Test
    fun valido_envia_solo_ids_y_cantidades_y_lee_el_pedido_guardado() = runTest {
        responder(201, pedidoGuardado)

        val pedido = repo.crearPedido(
            cliente = "Ana López", telefono = "4421234567", notas = "sin azúcar",
            ubicacion = null, fcmToken = null,
            carrito = listOf(CartItem(americano, 2), CartItem(pan, 1))
        )

        // Lo que se envió
        val peticion = servidor.takeRequest()
        assertEquals("POST", peticion.method)
        assertEquals("/api/pedidos.php", peticion.path)
        val cuerpo = JSONObject(peticion.body.readUtf8())
        val items = cuerpo.getJSONArray("items")
        assertEquals(2, items.length())
        assertEquals(1, items.getJSONObject(0).getInt("producto_id"))
        assertEquals(2, items.getJSONObject(0).getInt("cantidad"))
        // El precio NUNCA sale del teléfono: lo pone el servidor
        assertFalse(items.getJSONObject(0).has("precio"))
        assertFalse(cuerpo.has("total"))
        // Sin ubicación ni token no se mandan esas llaves
        assertFalse(cuerpo.has("latitud"))
        assertFalse(cuerpo.has("fcm_token"))

        // Lo que se interpretó (DECIMAL de MySQL llega como texto)
        assertEquals("CMD-7K3F9Q", pedido.folio)
        assertEquals(97.90, pedido.total, 0.001)
        assertEquals(2, pedido.items.size)
        assertEquals(35.0, pedido.items[0].unitPrice, 0.001)
        assertNull(pedido.latitude)
    }

    // ---------- Caso límite ----------

    @Test
    fun limite_respuesta_con_nulos_y_sin_renglones_no_truena() = runTest {
        // Pedido mínimo: sin notas, sin ubicación, sin folio (pedidos viejos) y sin "items"
        responder(201, """
            {"error": false, "pedido": {"id": 1, "cliente": "Ana", "telefono": "4421234567",
             "notas": null, "total": "0.00", "latitud": null, "longitud": null, "direccion": null}}
        """.trimIndent())

        val pedido = repo.crearPedido("Ana", "4421234567", "", null, null, listOf(CartItem(americano, 20)))

        assertEquals("", pedido.notes)
        assertEquals("", pedido.folio)
        assertEquals("#1", folioComanda(pedido))   // la comanda usa el id cuando no hay folio
        assertEquals("recibido", pedido.status)    // estado por defecto
        assertTrue(pedido.items.isEmpty())
        assertEquals(0.0, pedido.total, 0.0)
        // La cantidad máxima (20) se manda tal cual
        val items = JSONObject(servidor.takeRequest().body.readUtf8()).getJSONArray("items")
        assertEquals(20, items.getJSONObject(0).getInt("cantidad"))
    }

    // ---------- Casos inválidos / de error ----------

    @Test
    fun invalido_producto_con_id_no_numerico_se_rechaza_sin_usar_la_red() = runTest {
        val viejo = Product("abc", "Producto viejo", "", 10.0, "")
        try {
            repo.crearPedido("Ana", "4421234567", "", null, null, listOf(CartItem(viejo, 1)))
            fail("Debió lanzar ApiException")
        } catch (e: ApiException) {
            assertEquals("El producto \"Producto viejo\" no es válido. Actualiza el menú.", e.message)
        }
        assertEquals(0, servidor.requestCount)
    }

    @Test
    fun error_422_muestra_el_mensaje_del_servidor() = runTest {
        responder(422, """{"error": true, "message": "Algún producto ya no está disponible. Actualiza el menú."}""")
        try {
            repo.crearPedido("Ana", "4421234567", "", null, null, listOf(CartItem(americano, 1)))
            fail("Debió lanzar ApiException")
        } catch (e: ApiException) {
            assertEquals("Algún producto ya no está disponible. Actualiza el menú.", e.message)
        }
    }

    @Test
    fun error_500_sin_json_da_mensaje_generico() = runTest {
        responder(500, "<html>Internal Server Error</html>")
        try {
            repo.crearPedido("Ana", "4421234567", "", null, null, listOf(CartItem(americano, 1)))
            fail("Debió lanzar ApiException")
        } catch (e: ApiException) {
            assertEquals("Error del servidor (500). Intenta más tarde.", e.message)
        }
    }

    @Test
    fun error_html_con_codigo_200_se_reporta_como_respuesta_no_valida() = runTest {
        // Pasa con algunos hostings que muestran una página de aviso en lugar del PHP
        responder(200, "<html><body>Sitio suspendido</body></html>")
        try {
            repo.consultarPorFolio("CMD-7K3F9Q")
            fail("Debió lanzar ApiException")
        } catch (e: ApiException) {
            assertEquals("La respuesta del servidor no es válida.", e.message)
        }
    }
}
