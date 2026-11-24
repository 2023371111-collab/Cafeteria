package com.example.cafeteria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PRÁCTICA 11 · WHITE BOX · Método 1 (validación): ValidadorAcceso.validar()
 *
 * Nodos del grafo de flujo (ver docs/practica11):
 *   N1  limpio = usuario.trim()
 *   D1  usuario.isBlank() || contrasena.isEmpty()          C1a, C1b
 *   D2  limpio.length > 30 || contrasena.length > 64       C2a, C2b
 *   D3  contrasena.length < 8
 *   D4  !REGEX_USUARIO.matches(limpio)
 *   R1..R4 rechazos, R5 null (válido)
 * Complejidad ciclomática V(G) = decisiones + 1 = 4 + 1 = 5  (con condiciones atómicas: 6 + 1 = 7)
 *
 * Caminos independientes (base):
 *   P1  D1=V                         → R1 campos vacíos
 *   P2  D1=F, D2=V                   → R2 longitud excedida
 *   P3  D1=F, D2=F, D3=V             → R3 contraseña corta
 *   P4  D1=F, D2=F, D3=F, D4=V       → R4 formato no permitido
 *   P5  D1=F, D2=F, D3=F, D4=F       → R5 válido
 * Cobertura de condiciones: C1a, C1b, C2a, C2b toman V y F (casos CC-*).
 */
class WB01ValidarAccesoTest {

    private fun motivo(u: String, c: String) = ValidadorAcceso.validar(u, c)?.motivoLog

    // ---------- Caminos independientes (cobertura de instrucciones y decisiones) ----------

    @Test fun p1_campos_vacios() = assertEquals("campos vacíos", motivo("", "Secreta123"))
    @Test fun p2_longitud_excedida() = assertEquals("longitud excedida", motivo("a".repeat(31), "Secreta123"))
    @Test fun p3_contrasena_corta() = assertEquals("contraseña demasiado corta", motivo("ana.lopez", "1234567"))
    @Test fun p4_formato_no_permitido() = assertEquals("formato de usuario no permitido", motivo("ana' OR 1=1", "Secreta123"))
    @Test fun p5_valido() = assertNull(ValidadorAcceso.validar("ana.lopez", "Secreta123"))

    // ---------- Cobertura de condiciones (cada condición atómica en V y en F) ----------

    /** C1a=V (usuario solo espacios; isBlank, no isEmpty). */
    @Test fun cc_c1a_verdadera() = assertEquals("campos vacíos", motivo("   ", "Secreta123"))

    /** C1a=F, C1b=V. */
    @Test fun cc_c1b_verdadera() = assertEquals("campos vacíos", motivo("ana.lopez", ""))

    /** C2a=F, C2b=V: el usuario cabe pero la contraseña pasa de 64. */
    @Test fun cc_c2b_verdadera() = assertEquals("longitud excedida", motivo("ana.lopez", "x".repeat(65)))

    /** C2a=V, C2b=V a la vez: sigue siendo el mismo rechazo. */
    @Test fun cc_c2a_y_c2b_verdaderas() = assertEquals("longitud excedida", motivo("a".repeat(40), "x".repeat(80)))

    // ---------- Valores límite ----------

    @Test fun limite_usuario_30_acepta_31_rechaza() {
        assertNull(ValidadorAcceso.validar("a".repeat(30), "Secreta123"))
        assertEquals("longitud excedida", motivo("a".repeat(31), "Secreta123"))
    }

    /** El límite se mide DESPUÉS de trim(): 30 letras con espacios alrededor se aceptan. */
    @Test fun limite_usuario_se_mide_sin_espacios() =
        assertNull(ValidadorAcceso.validar("  " + "a".repeat(30) + "  ", "Secreta123"))

    @Test fun limite_contrasena_7_8_64_65() {
        assertEquals("contraseña demasiado corta", motivo("ana", "x".repeat(7)))
        assertNull(ValidadorAcceso.validar("ana", "x".repeat(8)))
        assertNull(ValidadorAcceso.validar("ana", "x".repeat(64)))
        assertEquals("longitud excedida", motivo("ana", "x".repeat(65)))
    }

    @Test fun limite_usuario_de_1_caracter_se_acepta_en_login() =
        assertNull(ValidadorAcceso.validar("a", "Secreta123"))

    // ---------- Entradas maliciosas (validación de entradas) ----------

    @Test fun inyeccion_y_script_se_rechazan_antes_de_la_red() {
        listOf("admin'--", "a\"b", "x;DROP TABLE", "<script>", "ana lopez", "ñandú").forEach {
            assertEquals(it, "formato de usuario no permitido", motivo(it, "Secreta123"))
        }
    }

    /** El motivo que va a Logcat nunca contiene lo que escribió el usuario. */
    @Test fun el_rechazo_no_expone_credenciales() {
        val r = ValidadorAcceso.validar("ana'--", "MiClave999")!!
        assert(!r.motivoLog.contains("ana") && !r.motivoLog.contains("MiClave"))
        assertEquals(ValidadorAcceso.MSG_DATOS_INVALIDOS, r.mensaje)
    }
}
