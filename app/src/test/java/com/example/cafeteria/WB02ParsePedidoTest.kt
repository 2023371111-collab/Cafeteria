package com.example.cafeteria

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * PRÁCTICA 11 · WHITE BOX · Método 2 (procesamiento): OrderRepository.parsePedido()
 * Convierte el JSON del servidor en un Order. Es privado, se ejecuta a través de
 * consultarPorFolio() con un servidor falso (MockWebServer).
 *
 * Decisiones del método:
 *   D1  optJSONArray("items") ?: JSONArray()     (llega / no llega la lista)
 *   D2  isNull("notas")
 *   D3  isNull("latitud")
 *   D4  isNull("longitud")
 *   D5  isNull("direccion")
 *   D6  optInt("avisable", 0) == 1
 *   D7  ciclo List(items.length()) { ... }       (0 vueltas / 1 o más)
 * V(G) = 7 + 1 = 8 caminos independientes.
 */
class WB02ParsePedidoTest {

    private lateinit var servidor: MockWebServer
    private lateinit var repo: OrderRepository

    @Before fun preparar() {
        servidor = MockWebServer().apply { start() }
        repo = OrderRepository(baseUrl = servidor.url("/api/").toString(), reintentos = PoliticaReintentos.SIN_REINTENTOS)
    }

    @After fun cerrar() = servidor.shutdown()

    private suspend fun parsear(pedidoJson: String): Order {
        servidor.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"error":false,"total":1,"pedidos":[$pedidoJson]}"""))
        return repo.consultarPorFolio("CMD-7K3F9Q").single()
    }

    private val base = """"id":7,"folio":"CMD-7K3F9Q","cliente":"Ana","telefono":"4421234567","total":"97.90""""

    /** Camino 1: todos los opcionales presentes (D1..D6 = rama "con valor", D7 = 2 vueltas). */
    @Test fun c1_pedido_completo() = runTest {
        val p = parsear("""{$base,"notas":"sin azúcar","estado":"listo","creado_en":"2026-11-10 09:00:00",
            "latitud":"20.588793","longitud":"-100.389888","direccion":"UTEQ","avisable":1,
            "items":[{"producto_id":1,"nombre":"Americano","precio_unitario":"35.00","cantidad":2},
                     {"producto_id":3,"nombre":"Pan","precio_unitario":"27.90","cantidad":1}]}""")
        assertEquals("sin azúcar", p.notes)
        assertEquals(20.588793, p.latitude!!, 1e-9)
        assertEquals(-100.389888, p.longitude!!, 1e-9)
        assertEquals("UTEQ", p.deliveryAddress)
        assertTrue(p.avisable)
        assertEquals(2, p.items.size)
        assertEquals(97.90, p.total, 0.001) // DECIMAL llega como texto y se convierte
        assertEquals(35.0 * 2 + 27.9, p.items.sumOf { it.unitPrice * it.quantity }, 0.001)
    }

    /** Camino 2: todos los opcionales en null y sin "items" (D1 = elvis, D7 = 0 vueltas). */
    @Test fun c2_opcionales_nulos_y_sin_items() = runTest {
        val p = parsear("""{$base,"notas":null,"latitud":null,"longitud":null,"direccion":null}""")
        assertEquals("", p.notes)
        assertNull(p.latitude); assertNull(p.longitude); assertNull(p.deliveryAddress)
        assertFalse(p.avisable)
        assertTrue(p.items.isEmpty())
        assertEquals("recibido", p.status)   // valor por omisión de optString
        assertEquals("", p.createdAt)
    }

    /** Camino 3: latitud sí, longitud no (D3 y D4 son independientes). */
    @Test fun c3_solo_latitud() = runTest {
        val p = parsear("""{$base,"notas":null,"latitud":20.5,"longitud":null,"direccion":null}""")
        assertEquals(20.5, p.latitude!!, 0.0)
        assertNull(p.longitude)
    }

    /** Camino 4: avisable = 0 → false; avisable = 2 también es false (solo 1 cuenta). */
    @Test fun c4_avisable_distinto_de_1() = runTest {
        assertFalse(parsear("""{$base,"notas":null,"latitud":null,"longitud":null,"direccion":null,"avisable":0}""").avisable)
        assertFalse(parsear("""{$base,"notas":null,"latitud":null,"longitud":null,"direccion":null,"avisable":2}""").avisable)
    }

    /** Valor límite: lista de items vacía (0 vueltas, pero con la lista presente). */
    @Test fun limite_items_vacio() = runTest {
        assertTrue(parsear("""{$base,"notas":null,"latitud":null,"longitud":null,"direccion":null,"items":[]}""").items.isEmpty())
    }

    /**
     * EXCEPCIÓN. Práctica 11: DEF-07, salía una JSONException sin traducir.
     * Práctica 12 (corregido): parsePedido corre dentro de leer{} y sale un ApiException
     * con el mensaje "La respuesta del servidor no es válida." (la causa sigue siendo la JSONException).
     */
    @Test fun excepcion_campo_obligatorio_faltante_se_traduce_a_ApiException() = runTest {
        try {
            parsear("""{"id":7,"telefono":"4421234567","total":"10.00"}""")
            fail("Debió lanzar una excepción")
        } catch (e: ApiException) {
            assertEquals("La respuesta del servidor no es válida.", e.message)
            assertTrue(e.cause is JSONException && e.cause!!.message!!.contains("cliente"))
            assertFalse(e.reintentable)   // repetir no arregla un JSON mal formado
        }
    }

    /** EXCEPCIÓN: total no numérico ("abc") también se traduce a ApiException. */
    @Test fun excepcion_total_no_numerico() = runTest {
        try {
            parsear("""{"id":7,"cliente":"Ana","telefono":"4421234567","total":"abc"}""")
            fail("Debió lanzar una excepción")
        } catch (e: ApiException) {
            assertTrue(e.cause is JSONException)
        }
    }
}
