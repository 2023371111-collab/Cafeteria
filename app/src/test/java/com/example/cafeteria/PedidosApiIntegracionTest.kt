package com.example.cafeteria

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * Pruebas de INTEGRACIÓN: OrderRepository (cliente HTTP real) ↔ pedidos.php (PHP real)
 * ↔ MySQL de pruebas. No hay mocks: la petición sale por la red local y se guarda en la base.
 *
 * Requiere el entorno de backend/pruebas levantado:
 *   docker compose -f backend/pruebas/docker-compose.yml up -d --build
 *   gradlew :app:testDebugUnitTest -PapiPruebasUrl=http://localhost:8080/api/
 * Sin -PapiPruebasUrl estas pruebas se marcan como "skipped" (no fallan).
 */
class PedidosApiIntegracionTest {

    private val url = System.getProperty("api.pruebas.url").orEmpty()
    private lateinit var repo: OrderRepository

    // Productos de backend/pruebas/datos_prueba.sql. El precio que trae el teléfono
    // es FALSO a propósito: el servidor debe ignorarlo y usar el de la base.
    private val americano = Product("1", "Americano de prueba", "", 0.01, "")
    private val pan = Product("3", "Pan de elote de prueba", "", 0.01, "")
    private val descontinuado = Product("4", "Frappé descontinuado", "", 55.0, "")

    /** Teléfono distinto en cada prueba para que no se mezclen los pedidos. */
    private val telefono = "55" + Random.nextInt(10_000_000, 99_999_999)

    @Before
    fun preparar() {
        assumeTrue("Sin servidor de pruebas (-PapiPruebasUrl)", url.isNotBlank())
        repo = OrderRepository(baseUrl = url)
    }

    @Test
    fun pedido_valido_se_guarda_con_precios_del_servidor_y_se_consulta_por_folio() = runBlocking {
        val creado = repo.crearPedido(
            cliente = "Cliente Integración", telefono = telefono, notas = "sin azúcar",
            ubicacion = Ubicacion(20.654321, -100.404321, direccion = "Edificio de pruebas"),
            fcmToken = null,
            carrito = listOf(CartItem(americano, 2), CartItem(pan, 1))
        )

        // El servidor calculó el total con SUS precios: 2 × 35.00 + 1 × 27.90
        assertEquals(97.90, creado.total, 0.001)
        assertTrue("Folio con formato CMD-XXXXXX: ${creado.folio}", ValidadorPedido.folio(creado.folio) == null)
        assertEquals("recibido", creado.status)

        // Persistencia: se vuelve a leer de MySQL por folio y por teléfono
        val porFolio = repo.consultarPorFolio(creado.folio)
        assertEquals(1, porFolio.size)
        val guardado = porFolio[0]
        assertEquals(creado.id, guardado.id)
        assertEquals(listOf(1 to 2, 3 to 1), guardado.items.map { it.productId to it.quantity })
        assertEquals(35.0, guardado.items[0].unitPrice, 0.001)
        assertEquals(20.654321, guardado.latitude!!, 0.0000001)
        assertEquals("Edificio de pruebas", guardado.deliveryAddress)

        val porTelefono = repo.consultarPedidos(telefono)
        assertEquals(listOf(creado.folio), porTelefono.map { it.folio })
    }

    @Test
    fun producto_no_disponible_se_rechaza_y_no_se_guarda_nada() = runBlocking {
        try {
            repo.crearPedido("Cliente Integración", telefono, "", null, null,
                listOf(CartItem(americano, 1), CartItem(descontinuado, 1)))
            fail("El servidor debió rechazar el pedido")
        } catch (e: ApiException) {
            assertEquals("Algún producto ya no está disponible. Actualiza el menú.", e.message)
        }
        // Ni el encabezado ni el renglón válido quedaron en la base
        assertTrue(repo.consultarPedidos(telefono).isEmpty())
    }

    @Test
    fun mas_de_20_piezas_de_un_producto_se_rechaza_en_el_servidor() = runBlocking {
        // El carrito de la app no tiene tope, pero el servidor sí (MAX_CANTIDAD = 20)
        try {
            repo.crearPedido("Cliente Integración", telefono, "", null, null, listOf(CartItem(americano, 21)))
            fail("El servidor debió rechazar el pedido")
        } catch (e: ApiException) {
            assertEquals("Máximo 20 piezas de un mismo producto.", e.message)
        }
        assertTrue(repo.consultarPedidos(telefono).isEmpty())
    }

    @Test
    fun folio_inexistente_regresa_lista_vacia() = runBlocking {
        assertTrue(repo.consultarPorFolio("CMD-ZZZZZZ").isEmpty())
    }
}
