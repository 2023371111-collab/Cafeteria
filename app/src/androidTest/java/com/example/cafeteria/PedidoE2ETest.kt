package com.example.cafeteria

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Prueba E2E del flujo crítico: iniciar sesión → agregar productos al carrito →
 * confirmar el pedido → ver la comanda con su folio → consultar ese folio en "Pedidos".
 * Usa la app real contra la API y MySQL de pruebas (backend/pruebas), nada simulado.
 *
 *   docker compose -f backend/pruebas/docker-compose.yml up -d --build
 *   gradlew :app:connectedDebugAndroidTest -PapiUrl=http://10.0.2.2:8080/api/
 */
@RunWith(AndroidJUnit4::class)
class PedidoE2ETest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Evita el diálogo del sistema "¿Permitir notificaciones?" al enviar el pedido
    @get:Rule
    val permisos: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Before
    fun preparar() {
        // Nunca contra producción: crearía pedidos reales
        assumeTrue("Compila con -PapiUrl del servidor de pruebas", BuildConfig.API_URL.contains("10.0.2.2"))
        // Empieza sin sesión, con el carrito vacío y sin datos recordados
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf("sesion_prefs", "cart_prefs", "pedidos_prefs").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun registrar_pedido_y_encontrarlo_por_folio() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // 1. Iniciar sesión con la cuenta ficticia de datos_prueba.sql
            campo("Usuario").performTextInput("cliente.e2e")
            campo("Contraseña").performTextInput("PruebaE2E2026")
            compose.onNode(hasText("Iniciar sesión") and hasClickAction()).performClick()
            esperarTexto("Continuar")
            compose.onNodeWithText("Continuar").performClick()

            // 2. El menú viene de la API de pruebas; se agregan 2 americanos ($35.00 c/u)
            esperarTexto("Americano de prueba")
            repeat(2) { compose.onAllNodesWithText("Agregar").onFirst().performClick() }
            captura("01_menu_api_pruebas")

            // 3. Carrito → Confirmar pedido → capturar datos → enviar
            compose.onNodeWithText("Carrito").performClick()
            compose.onNodeWithText("Confirmar Pedido").performClick()
            campo("Nombre").performTextInput("Cliente Pruebas") // sin dígitos: la regla del nombre solo admite letras
            campo("Teléfono").performTextInput("5512345678")
            captura("02_formulario_lleno")
            compose.onNodeWithText("☁️ Enviar pedido").performScrollTo().performClick()

            // 4. Resultado: comanda con folio generado por el servidor y total calculado allá
            esperarTexto("✅ ¡Pedido enviado! Esta es tu comanda", timeoutMs = 20_000)
            val folio = textoQueCoincide(Regex("^CMD-[0-9A-Z]{6}$"))
            assertTrue(compose.onAllNodesWithText("$70.00").fetchSemanticsNodes().isNotEmpty())
            captura("03_comanda_con_folio")

            // 5. Persistencia: se consulta ese folio en la pestaña "Pedidos" (nueva petición a MySQL)
            compose.onNodeWithText("🧾 Ver mis pedidos").performClick()
            campo("Teléfono o folio").performTextReplacement(folio)
            compose.onNodeWithText("Consultar").performClick()
            esperarTexto("Pedido $folio", timeoutMs = 15_000)
            compose.onNodeWithText("2 × Americano de prueba").assertExists()
            captura("04_consulta_por_folio")
        }
    }

    /** Captura de pantalla como evidencia; queda en Android/data/com.example.cafeteria/files/e2e/. */
    private fun captura(nombre: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val carpeta = File(instrumentation.targetContext.getExternalFilesDir(null), "e2e").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(carpeta, "$nombre.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun campo(etiqueta: String) = compose.onNode(hasSetTextAction() and hasText(etiqueta))

    private fun ComposeTestRule.hayTexto(texto: String) =
        onAllNodesWithText(texto).fetchSemanticsNodes().isNotEmpty()

    private fun esperarTexto(texto: String, timeoutMs: Long = 10_000) {
        try {
            compose.waitUntil(timeoutMs) { compose.hayTexto(texto) }
        } catch (e: ComposeTimeoutException) {
            // Deja en Logcat (tag PedidoE2E) lo que había en pantalla para diagnosticar la falla
            compose.onRoot().printToLog("PedidoE2E")
            throw e
        }
    }

    /** Devuelve el primer texto en pantalla que coincide con [regex] (ej. el folio). */
    private fun textoQueCoincide(regex: Regex): String {
        val matcher = SemanticsMatcher("texto ~ $regex") { nodo ->
            nodo.config.getOrNull(SemanticsProperties.Text).orEmpty().any { regex.matches(it.text) }
        }
        val nodo = compose.onAllNodes(matcher).fetchSemanticsNodes().first()
        return nodo.config[SemanticsProperties.Text].first { regex.matches(it.text) }.text
    }
}
