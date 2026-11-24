package com.example.cafeteria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ValidadorAccesoTest {

    private fun mensaje(usuario: String, contrasena: String) =
        ValidadorAcceso.validar(usuario, contrasena)?.mensaje

    @Test
    fun datos_validos() {
        assertNull(ValidadorAcceso.validar("operador.qro", "Kt#Acceso47"))
        assertNull(ValidadorAcceso.validar("  ana_lopez-2  ", "12345678"))
    }

    @Test
    fun campos_vacios() {
        assertEquals(ValidadorAcceso.MSG_CAMPOS_VACIOS, mensaje("", "Secreta123"))
        assertEquals(ValidadorAcceso.MSG_CAMPOS_VACIOS, mensaje("operador.qro", ""))
    }

    @Test
    fun usuario_solo_espacios() {
        assertEquals(ValidadorAcceso.MSG_CAMPOS_VACIOS, mensaje("     ", "Secreta123"))
        assertEquals(ValidadorAcceso.MSG_CAMPOS_VACIOS, mensaje(" \t ", "Secreta123"))
    }

    @Test
    fun longitud_excedida() {
        assertEquals(ValidadorAcceso.MSG_DATOS_INVALIDOS, mensaje("a".repeat(31), "Secreta123"))
        assertEquals(ValidadorAcceso.MSG_DATOS_INVALIDOS, mensaje("operador.qro", "x".repeat(65)))
        assertNull(ValidadorAcceso.validar("a".repeat(30), "x".repeat(64)))
    }

    @Test
    fun contrasena_minimo_8() {
        assertEquals(ValidadorAcceso.MSG_DATOS_INVALIDOS, mensaje("operador.qro", "abc1234"))
        assertNull(ValidadorAcceso.validar("operador.qro", "abc12345"))
    }

    @Test
    fun caracteres_no_permitidos() {
        assertEquals(ValidadorAcceso.MSG_DATOS_INVALIDOS, mensaje("admin'--", "Secreta123"))
        assertEquals(ValidadorAcceso.MSG_DATOS_INVALIDOS, mensaje("<script>", "Secreta123"))
        assertEquals(ValidadorAcceso.MSG_DATOS_INVALIDOS, mensaje("ana lopez", "Secreta123"))
    }

    // ----- Crear cuenta -----

    @Test
    fun registro_valido() {
        val e = ValidadorAcceso.validarRegistro("José Ángel", "jose.angel", "Cafe2026", "Cafe2026")
        assertFalse(e.hayErrores)
    }

    @Test
    fun registro_campos_con_error() {
        val e = ValidadorAcceso.validarRegistro("J1", "ab", "solotexto", "otra")
        assertNotNull(e.nombre)          // números en el nombre / muy corto
        assertNotNull(e.usuario)         // menos de 4 caracteres
        assertNotNull(e.contrasena)      // sin números
        assertNotNull(e.confirmacion)    // no coincide
        assertEquals("nombre, usuario, contraseña, confirmación", e.camposConError)
    }

    @Test
    fun registro_contrasena_sin_letras_o_corta() {
        assertNotNull(ValidadorAcceso.validarRegistro("Ana", "ana.lopez", "12345678", "12345678").contrasena)
        assertNotNull(ValidadorAcceso.validarRegistro("Ana", "ana.lopez", "abc123", "abc123").contrasena)
    }

    @Test
    fun registro_usuario_con_espacios_o_simbolos() {
        assertNotNull(ValidadorAcceso.validarRegistro("Ana", "ana lopez", "Cafe2026", "Cafe2026").usuario)
        assertNotNull(ValidadorAcceso.validarRegistro("Ana", "    ", "Cafe2026", "Cafe2026").usuario)
        assertNotNull(ValidadorAcceso.validarRegistro("Ana", "ana<b>", "Cafe2026", "Cafe2026").usuario)
    }

    @Test
    fun el_motivo_de_log_no_contiene_credenciales() {
        val rechazo = ValidadorAcceso.validar("admin'--", "Secreta123")!!
        assertFalse(rechazo.motivoLog.contains("admin"))
        assertFalse(rechazo.motivoLog.contains("Secreta123"))
    }
}
