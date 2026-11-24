package com.example.cafeteria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUT-01 · Prueba UNITARIA · Función crítica: inicio de sesión y registro (ValidadorAcceso).
 *
 * Riesgo que previene (práctica 5 / práctica 11): que una entrada vacía, enorme o con
 * caracteres de inyección llegue a login.php, o que el mensaje de error revele qué campo falló.
 * Si alguien modifica las reglas y rompe alguno de estos casos, el pipeline CI falla.
 */
class AUT01LoginValidacionTest {

    // ---------- Entrada válida / resultado esperado ----------

    @Test
    fun entrada_valida_se_acepta() {
        assertNull(ValidadorAcceso.validar("cliente.pruebas", "Pruebas2026"))
        // Los espacios alrededor del usuario se ignoran (trim)
        assertNull(ValidadorAcceso.validar("  ana_lopez-1  ", "12345678"))
    }

    @Test
    fun registro_valido_no_tiene_errores() {
        val errores = ValidadorAcceso.validarRegistro("Ana López", "ana.lopez", "Cafe2026", "Cafe2026")
        assertFalse(errores.hayErrores)
        assertEquals("", errores.camposConError)
    }

    // ---------- Entrada vacía ----------

    @Test
    fun campos_vacios_o_solo_espacios_se_rechazan_con_mensaje_generico() {
        listOf("" to "Pruebas2026", "    " to "Pruebas2026", "cliente" to "").forEach { (u, c) ->
            val rechazo = ValidadorAcceso.validar(u, c)
            assertNotNull("Debió rechazar usuario='$u'", rechazo)
            assertEquals(ValidadorAcceso.MSG_CAMPOS_VACIOS, rechazo!!.mensaje)
        }
    }

    @Test
    fun registro_vacio_marca_los_cuatro_campos() {
        val errores = ValidadorAcceso.validarRegistro("", "", "", "")
        assertTrue(errores.hayErrores)
        assertEquals("nombre, usuario, contraseña, confirmación", errores.camposConError)
    }

    // ---------- Valores fuera de rango (límites exactos) ----------

    @Test
    fun usuario_en_el_limite_se_acepta_y_uno_mas_se_rechaza() {
        val limite = "a".repeat(ValidadorAcceso.MAX_USUARIO)           // 30
        assertNull(ValidadorAcceso.validar(limite, "Pruebas2026"))
        val excedido = ValidadorAcceso.validar(limite + "a", "Pruebas2026") // 31
        assertEquals(ValidadorAcceso.MSG_DATOS_INVALIDOS, excedido!!.mensaje)
        assertEquals("longitud excedida", excedido.motivoLog)
    }

    @Test
    fun contrasena_fuera_de_rango_se_rechaza() {
        // 7 caracteres (mínimo 8) y 65 caracteres (máximo 64)
        assertEquals("contraseña demasiado corta", ValidadorAcceso.validar("cliente", "1234567")!!.motivoLog)
        assertNull(ValidadorAcceso.validar("cliente", "x".repeat(ValidadorAcceso.MAX_CONTRASENA)))
        assertEquals("longitud excedida", ValidadorAcceso.validar("cliente", "x".repeat(65))!!.motivoLog)
    }

    @Test
    fun registro_fuera_de_rango_indica_el_limite() {
        val errores = ValidadorAcceso.validarRegistro("Al", "abc", "abc12", "otra")
        assertEquals("Mínimo 3 letras", errores.nombre)
        assertEquals("Mínimo 4 caracteres", errores.usuario)
        assertEquals("Mínimo 8 caracteres", errores.contrasena)
        assertEquals("Las contraseñas no coinciden", errores.confirmacion)
    }

    // ---------- Entrada maliciosa: el mensaje nunca revela el dato ----------

    @Test
    fun caracteres_de_inyeccion_se_rechazan_sin_revelar_la_entrada() {
        val ataques = listOf("admin' OR '1'='1", "<script>", "ana;DROP", "ana lopez")
        ataques.forEach { ataque ->
            val rechazo = ValidadorAcceso.validar(ataque, "Pruebas2026")
            assertNotNull("Debió rechazar: $ataque", rechazo)
            assertEquals(ValidadorAcceso.MSG_DATOS_INVALIDOS, rechazo!!.mensaje)
            // Ni el mensaje para la persona ni el de Logcat contienen lo que se escribió
            assertFalse(rechazo.mensaje.contains(ataque))
            assertFalse(rechazo.motivoLog.contains(ataque))
        }
    }

    @Test
    fun contrasena_de_registro_sin_numeros_se_rechaza() {
        assertEquals("Debe tener letras y números",
            ValidadorAcceso.validarRegistro("Ana López", "ana.lopez", "solamenteletras", "solamenteletras").contrasena)
    }
}
