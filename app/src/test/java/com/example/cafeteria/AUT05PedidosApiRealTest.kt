package com.example.cafeteria

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.random.Random

/**
 * AUT-05 · Prueba de INTEGRACIÓN · App (repositorios) ↔ API PHP real ↔ MySQL 8.
 *
 * Sin dobles de prueba: el código PHP de backend/api corre en Docker (backend/pruebas) con una
 * base MySQL en memoria que solo tiene datos ficticios. En GitHub Actions el job "integracion"
 * levanta ese entorno; en local:
 *   docker compose -f backend/pruebas/docker-compose.yml up -d --build
 *   gradlew :app:testDebugUnitTest -PapiPruebasUrl=http://localhost:8080/api/
 * Sin -PapiPruebasUrl se marca como "skipped" (no falla).
 */
class AUT05PedidosApiRealTest {

    private val url = System.getProperty("api.pruebas.url").orEmpty()
    private lateinit var pedidos: OrderRepository
    private lateinit var auth: AuthRepository

    // Productos ficticios de datos_prueba.sql. El precio del teléfono es FALSO a propósito.
    private val americano = Product("1", "Americano de prueba", "", 0.01, "")
    private val capuchino = Product("2", "Capuchino de prueba", "", 0.01, "")
    private val descontinuado = Product("4", "Frappé descontinuado", "", 55.0, "")
    private val telefono = "56" + Random.nextInt(10_000_000, 99_999_999)

    @Before
    fun preparar() {
        assumeTrue("Sin servidor de pruebas (-PapiPruebasUrl)", url.isNotBlank())
        pedidos = OrderRepository(baseUrl = url, reintentos = PoliticaReintentos.SIN_REINTENTOS)
        auth = AuthRepository(baseUrl = url)
    }

    /** Código HTTP crudo de un endpoint (para comprobar el contrato de la API). */
    private fun codigoHttp(metodo: String, ruta: String, cuerpo: String? = null): Int {
        val c = URL(url + ruta).openConnection() as HttpURLConnection
        c.requestMethod = metodo
        if (cuerpo != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(cuerpo.toByteArray()) }
        }
        return c.responseCode.also { c.disconnect() }
    }

    @Test
    fun la_api_devuelve_los_codigos_http_esperados() {
        assertEquals(200, codigoHttp("GET", "productos.php"))
        assertEquals(200, codigoHttp("GET", "pedidos.php?telefono=$telefono"))
        // Datos inválidos → 4xx (nunca 500)
        val invalido = codigoHttp("POST", "pedidos.php", """{"cliente":"","telefono":"12","items":[]}""")
        assertTrue("Código recibido: $invalido", invalido in 400..499)
        assertEquals(401, codigoHttp("POST", "login.php", """{"usuario":"cliente.e2e","contrasena":"Incorrecta2026"}"""))
    }

    @Test
    fun login_real_con_cuenta_de_pruebas_devuelve_sesion() = runBlocking {
        val resultado = auth.iniciarSesion("cliente.e2e", "PruebaE2E2026")
        assertTrue("Resultado: $resultado", resultado is ResultadoLogin.Exito)
        assertEquals("Cliente Pruebas", (resultado as ResultadoLogin.Exito).sesion.nombre)
        assertEquals(ResultadoLogin.Rechazado, auth.iniciarSesion("cliente.e2e", "Incorrecta2026"))
    }

    @Test
    fun el_pedido_enviado_se_almacena_y_se_puede_consultar() = runBlocking {
        val creado = pedidos.crearPedido(
            cliente = "Cliente AUT", telefono = telefono, notas = "prueba CI",
            ubicacion = null, fcmToken = null,
            carrito = listOf(CartItem(americano, 1), CartItem(capuchino, 2))
        )
        // Total calculado con los precios de MySQL (35.00 + 2 × 48.50), no con los del teléfono
        assertEquals(132.0, creado.total, 0.001)
        assertEquals(null, ValidadorPedido.folio(creado.folio))

        // Persistencia en MySQL: se lee de nuevo por folio y por teléfono
        val guardado = pedidos.consultarPorFolio(creado.folio).single()
        assertEquals(creado.id, guardado.id)
        assertEquals("prueba CI", guardado.notes)
        assertEquals(listOf(1 to 1, 2 to 2), guardado.items.map { it.productId to it.quantity })
        assertTrue(pedidos.consultarPedidos(telefono).any { it.folio == creado.folio })
    }

    @Test
    fun el_mismo_pedido_reenviado_con_la_misma_clave_no_se_duplica() = runBlocking {
        val clave = UUID.randomUUID().toString()
        val primero = pedidos.crearPedido("Cliente AUT", telefono, "", null, null, listOf(CartItem(americano, 1)), clave)
        val segundo = pedidos.crearPedido("Cliente AUT", telefono, "", null, null, listOf(CartItem(americano, 1)), clave)
        assertEquals(primero.id, segundo.id)
        assertEquals(1, pedidos.consultarPedidos(telefono).size)
    }

    @Test
    fun producto_no_disponible_se_rechaza_con_mensaje_del_servidor() = runBlocking {
        try {
            pedidos.crearPedido("Cliente AUT", telefono, "", null, null, listOf(CartItem(descontinuado, 1)))
            fail("Debió rechazar el producto no disponible")
        } catch (e: ApiException) {
            assertTrue("Mensaje: ${e.message}", e.message!!.isNotBlank() && !e.message!!.contains("SQL"))
        }
        assertTrue(pedidos.consultarPedidos(telefono).isEmpty())
    }
}
