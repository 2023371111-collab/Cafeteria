package com.example.cafeteria

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AUT-04 · Prueba de INTEGRACIÓN · App (AuthRepository, cliente HTTP real) ↔ servicio login.php / registro.php.
 *
 * El servidor se reemplaza por MockWebServer, que escucha en un puerto real: la petición
 * viaja por HTTP y se revisa lo que la app envía y cómo interpreta cada código HTTP.
 * Así corre en GitHub Actions sin depender del hosting de producción.
 */
class AUT04LoginIntegracionTest {

    private lateinit var servidor: MockWebServer
    private lateinit var repo: AuthRepository

    @Before
    fun preparar() {
        servidor = MockWebServer()
        servidor.start()
        repo = AuthRepository(baseUrl = servidor.url("/api/").toString())
    }

    @After
    fun cerrar() {
        servidor.shutdown()
    }

    private fun json(codigo: Int, cuerpo: String) =
        MockResponse().setResponseCode(codigo).setHeader("Content-Type", "application/json").setBody(cuerpo)

    @Test
    fun la_app_envia_credenciales_por_POST_y_recibe_una_sesion_valida() = runTest {
        servidor.enqueue(json(200, """{"usuario":"barista1","nombre":"Luis","rol":"barista","token":"abc123"}"""))

        val resultado = repo.iniciarSesion("barista1", "Pruebas2026")

        // Respuesta válida → sesión con el rol y token del servidor
        assertTrue(resultado is ResultadoLogin.Exito)
        val sesion = (resultado as ResultadoLogin.Exito).sesion
        assertEquals(Sesion("barista1", "Luis", "barista", "abc123"), sesion)
        assertTrue(sesion.esBarista)

        // Lo que llegó al servidor: POST JSON a login.php, la contraseña NUNCA en la URL
        val peticion = servidor.takeRequest()
        assertEquals("POST", peticion.method)
        assertEquals("/api/login.php", peticion.path)
        assertFalse(peticion.path!!.contains("Pruebas2026"))
        assertTrue(peticion.getHeader("Content-Type")!!.startsWith("application/json"))
        val cuerpo = JSONObject(peticion.body.readUtf8())
        assertEquals("barista1", cuerpo.getString("usuario"))
        assertEquals("Pruebas2026", cuerpo.getString("contrasena"))
    }

    @Test
    fun codigo_401_se_interpreta_como_credenciales_rechazadas() = runTest {
        servidor.enqueue(json(401, """{"error":true,"message":"Usuario no existe"}"""))
        assertEquals(ResultadoLogin.Rechazado, repo.iniciarSesion("nadie", "Incorrecta1"))
    }

    @Test
    fun codigo_500_muestra_mensaje_generico_sin_detalles_del_servidor() = runTest {
        servidor.enqueue(json(500, """{"error":true,"message":"SQLSTATE[HY000] Access denied for user"}"""))
        val resultado = repo.iniciarSesion("cliente", "Pruebas2026")
        assertEquals(ResultadoLogin.Fallo(ValidadorAcceso.MSG_ERROR_GENERAL), resultado)
    }

    @Test
    fun registro_con_usuario_ocupado_409_muestra_el_mensaje_del_servidor() = runTest {
        servidor.enqueue(json(409, """{"error":true,"message":"Ese usuario ya está registrado."}"""))
        val resultado = repo.registrar("Ana López", "ana.lopez", "Cafe2026")
        assertEquals(ResultadoLogin.Fallo("Ese usuario ya está registrado."), resultado)
        assertEquals("/api/registro.php", servidor.takeRequest().path)
    }

    @Test
    fun respuesta_html_en_lugar_de_json_no_cierra_la_app() = runTest {
        servidor.enqueue(MockResponse().setResponseCode(200).setBody("<html>Error 502</html>"))
        assertEquals(ResultadoLogin.Fallo(ValidadorAcceso.MSG_ERROR_GENERAL), repo.iniciarSesion("cliente", "Pruebas2026"))
    }

    @Test
    fun servidor_que_no_responde_muestra_mensaje_de_conexion() = runTest {
        // El servidor acepta la conexión y nunca contesta: la app espera su tiempo límite
        // (ApiConfig.TIMEOUT_MS = 8 s) y muestra un mensaje en vez de quedarse colgada
        servidor.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(ResultadoLogin.Fallo(ValidadorAcceso.MSG_SIN_CONEXION), repo.iniciarSesion("cliente", "Pruebas2026"))
    }

    @Test
    fun servidor_apagado_muestra_mensaje_de_conexion() = runTest {
        val url = servidor.url("/api/").toString()
        servidor.shutdown() // ya nadie escucha en ese puerto
        val resultado = AuthRepository(baseUrl = url).iniciarSesion("cliente", "Pruebas2026")
        assertEquals(ResultadoLogin.Fallo(ValidadorAcceso.MSG_SIN_CONEXION), resultado)
    }
}
