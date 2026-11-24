package com.example.cafeteria

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AUT-03 · Prueba UNITARIA · Manejo de excepciones y política de reintentos (Reintentos.kt).
 *
 * Riesgo que previene (práctica 12): reintentar un error 4xx (repetiría un rechazo), no
 * reintentar una caída temporal 5xx, o esperar sin límite. Los tiempos se controlan con un
 * "dormir" falso para que la prueba no tarde.
 */
class AUT03ReintentosExcepcionesTest {

    private val esperas = mutableListOf<Long>()

    /** Política sin azar (factor 1.0) y sin dormir de verdad: solo registra cuánto esperaría. */
    private fun politica(intentos: Int = 3) =
        PoliticaReintentos(intentos = intentos, azar = { 0.5 }, dormir = { esperas += it })

    // ---------- Entrada fuera de rango → excepción ----------

    @Test
    fun cero_intentos_lanza_IllegalArgumentException() {
        try {
            PoliticaReintentos(intentos = 0)
            fail("Debió lanzar IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Debe haber al menos un intento", e.message)
        }
    }

    // ---------- Resultado esperado ----------

    @Test
    fun espera_progresiva_respeta_el_maximo() {
        val p = politica()
        assertEquals(500L, p.esperaDespuesDe(1))
        assertEquals(1_000L, p.esperaDespuesDe(2))
        assertEquals(2_000L, p.esperaDespuesDe(3))
        assertEquals(4_000L, p.esperaDespuesDe(4))
        // Fuera de rango: un intento enorme no desborda, se queda en el tope
        assertEquals(4_000L, p.esperaDespuesDe(1_000))
    }

    @Test
    fun el_azar_queda_entre_75_y_125_por_ciento() {
        assertEquals(375L, PoliticaReintentos(azar = { 0.0 }).esperaDespuesDe(1))
        assertEquals(625L, PoliticaReintentos(azar = { 1.0 }).esperaDespuesDe(1))
    }

    @Test
    fun error_temporal_se_reintenta_y_luego_tiene_exito() = runTest {
        var llamadas = 0
        val resultado = politica().ejecutar {
            llamadas++
            if (it < 3) throw ApiException("503", reintentable = true)
            "ok"
        }
        assertEquals("ok", resultado)
        assertEquals(3, llamadas)
        assertEquals(listOf(500L, 1_000L), esperas)
    }

    // ---------- Manejo de excepciones ----------

    @Test
    fun error_4xx_no_se_reintenta_y_se_propaga_la_misma_excepcion() = runTest {
        val rechazo = ApiException("Datos inválidos", reintentable = false)
        var llamadas = 0
        try {
            politica().ejecutar { llamadas++; throw rechazo }
            fail("Debió propagar la excepción")
        } catch (e: ApiException) {
            assertSame(rechazo, e)
        }
        assertEquals(1, llamadas)
        assertTrue(esperas.isEmpty())
    }

    @Test
    fun al_agotar_los_intentos_se_lanza_el_ultimo_error() = runTest {
        var llamadas = 0
        try {
            politica(intentos = 3).ejecutar { llamadas++; throw ApiException("caído $it", reintentable = true) }
            fail("Debió lanzar ApiException")
        } catch (e: ApiException) {
            assertEquals("caído 3", e.message)
        }
        assertEquals(3, llamadas)
    }

    @Test
    fun excepcion_que_no_es_de_red_no_se_reintenta() = runTest {
        var llamadas = 0
        try {
            politica().ejecutar { llamadas++; throw IllegalStateException("error de programación") }
            fail("Debió lanzar IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals(1, llamadas)
        }
    }

    @Test
    fun solo_408_429_y_5xx_son_reintentables() {
        listOf(408, 429, 500, 503, 599).forEach { assertTrue("$it", codigoReintentable(it)) }
        listOf(200, 400, 401, 404, 409, 499, 600).forEach { assertFalse("$it", codigoReintentable(it)) }
    }
}
