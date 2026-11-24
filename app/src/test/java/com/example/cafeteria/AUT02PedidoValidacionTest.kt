package com.example.cafeteria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AUT-02 · Prueba UNITARIA · Función crítica: captura de un pedido (ValidadorPedido).
 *
 * Riesgo que previene: que se envíe a pedidos.php un pedido con teléfono incompleto,
 * coordenadas imposibles (fuera del planeta o NaN del GPS) o notas que excedan la columna.
 */
class AUT02PedidoValidacionTest {

    // ---------- Entrada válida / resultado esperado ----------

    @Test
    fun datos_validos_no_tienen_error() {
        assertNull(ValidadorPedido.nombre("María José O'Neil"))
        assertNull(ValidadorPedido.telefono("4421234567"))
        assertNull(ValidadorPedido.folio("CMD-7K3F9Q"))
        assertNull(ValidadorPedido.notas("Sin azúcar, por favor"))
        assertNull(ValidadorPedido.ubicacion(20.6543, -100.4043)) // UTEQ, Querétaro
    }

    @Test
    fun normalizar_folio_produce_el_formato_esperado() {
        assertEquals("CMD-7K3F9Q", ValidadorPedido.normalizarFolio("cmd7k3f9q"))
        assertEquals("CMD-7K3F9Q", ValidadorPedido.normalizarFolio("7k3f9q"))
        assertEquals("CMD-7K3F9Q", ValidadorPedido.normalizarFolio(" cmd-7k3f9q "))
        // Lo que no parece folio se deja (en mayúsculas) para que la validación lo rechace
        assertEquals("ABC", ValidadorPedido.normalizarFolio("abc"))
        assertEquals("ana lópez", ValidadorPedido.limpiarEspacios("  ana   lópez "))
    }

    // ---------- Entrada vacía ----------

    @Test
    fun campos_vacios_piden_capturar_el_dato() {
        assertEquals("Escribe tu nombre", ValidadorPedido.nombre("   "))
        assertEquals("Escribe tu teléfono", ValidadorPedido.telefono(""))
        assertEquals("Escribe el folio", ValidadorPedido.folio(" "))
        assertNull("Las notas son opcionales", ValidadorPedido.notas(""))
    }

    // ---------- Valores fuera de rango ----------

    @Test
    fun telefono_con_9_u_11_digitos_o_letras_se_rechaza() {
        listOf("442123456", "44212345678", "442-123-45", "44212345a7").forEach {
            assertEquals("Teléfono: $it", "Debe tener 10 dígitos", ValidadorPedido.telefono(it))
        }
    }

    @Test
    fun notas_en_el_limite_se_aceptan_y_una_letra_mas_se_rechaza() {
        assertNull(ValidadorPedido.notas("n".repeat(ValidadorPedido.MAX_NOTAS)))
        assertEquals("Máximo 150 caracteres", ValidadorPedido.notas("n".repeat(ValidadorPedido.MAX_NOTAS + 1)))
    }

    @Test
    fun coordenadas_fuera_del_planeta_o_nan_se_rechazan() {
        // Límites exactos válidos
        assertNull(ValidadorPedido.ubicacion(90.0, 180.0))
        assertNull(ValidadorPedido.ubicacion(-90.0, -180.0))
        // Fuera de rango y valores no numéricos (GPS sin señal)
        assertEquals("Ubicación no válida", ValidadorPedido.ubicacion(90.0001, 0.0))
        assertEquals("Ubicación no válida", ValidadorPedido.ubicacion(0.0, -180.5))
        assertEquals("Ubicación no válida", ValidadorPedido.ubicacion(Double.NaN, 0.0))
    }

    @Test
    fun nombre_de_61_letras_o_con_numeros_se_rechaza() {
        assertNull(ValidadorPedido.nombre("a".repeat(60)))
        assertEquals("Solo letras y espacios (máximo 60)", ValidadorPedido.nombre("a".repeat(61)))
        assertEquals("Solo letras y espacios (máximo 60)", ValidadorPedido.nombre("Ana123"))
        assertEquals("Mínimo 3 letras", ValidadorPedido.nombre("Al"))
    }
}
