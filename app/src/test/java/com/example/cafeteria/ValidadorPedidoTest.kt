package com.example.cafeteria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ValidadorPedidoTest {

    @Test
    fun nombre_valido_con_acentos() {
        assertNull(ValidadorPedido.nombre("José Ángel"))
        assertNull(ValidadorPedido.nombre("  Ana  "))
    }

    @Test
    fun nombre_invalido() {
        assertNotNull(ValidadorPedido.nombre(""))
        assertNotNull(ValidadorPedido.nombre("Al"))
        assertNotNull(ValidadorPedido.nombre("Ana123"))
        assertNotNull(ValidadorPedido.nombre("<script>"))
        assertNotNull(ValidadorPedido.nombre("a".repeat(61)))
    }

    @Test
    fun telefono_de_10_digitos() {
        assertNull(ValidadorPedido.telefono("4421234567"))
        assertNotNull(ValidadorPedido.telefono(""))
        assertNotNull(ValidadorPedido.telefono("442123456"))
        assertNotNull(ValidadorPedido.telefono("44212345678"))
        assertNotNull(ValidadorPedido.telefono("442-123-45"))
    }

    @Test
    fun notas_maximo_150() {
        assertNull(ValidadorPedido.notas(""))
        assertNull(ValidadorPedido.notas("x".repeat(150)))
        assertNotNull(ValidadorPedido.notas("x".repeat(151)))
    }

    @Test
    fun ubicacion_en_rango() {
        assertNull(ValidadorPedido.ubicacion(20.5888, -100.3899)) // Querétaro
        assertNull(ValidadorPedido.ubicacion(-90.0, 180.0))
        assertNotNull(ValidadorPedido.ubicacion(91.0, 0.0))
        assertNotNull(ValidadorPedido.ubicacion(0.0, -180.1))
        assertNotNull(ValidadorPedido.ubicacion(Double.NaN, 0.0))
    }

    @Test
    fun limpia_espacios_repetidos() {
        assertEquals("Ana López", ValidadorPedido.limpiarEspacios("  Ana    López "))
    }

    @Test
    fun formatea_fecha_de_mysql() {
        assertEquals("12/10/2026 19:05", formatearFecha("2026-10-12 19:05:00"))
        assertEquals("texto raro", formatearFecha("texto raro"))
    }
}
