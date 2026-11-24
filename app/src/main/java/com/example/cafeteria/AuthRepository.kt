package com.example.cafeteria

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cuenta que el servidor confirmó. No guarda la contraseña.
 * [token] lo entrega login.php para las acciones del barista; vacío si no hay
 * (cuenta recién creada o sesión guardada antes de esta versión).
 */
data class Sesion(val usuario: String, val nombre: String, val rol: String = "cliente", val token: String = "") {
    val esBarista: Boolean get() = rol == "barista" && token.isNotEmpty()
}

/** Resultado del login con un mensaje ya genérico para mostrarlo tal cual. */
sealed interface ResultadoLogin {
    data class Exito(val sesion: Sesion) : ResultadoLogin
    /** Usuario/contraseña incorrectos o cuenta bloqueada (el servidor no dice cuál). */
    data object Rechazado : ResultadoLogin
    data class Fallo(val mensaje: String) : ResultadoLogin
}

/**
 * Inicio de sesión (login.php) y creación de cuentas (registro.php).
 * Práctica 13: [baseUrl] se puede cambiar en las pruebas de integración para apuntar a un
 * servidor falso (MockWebServer), igual que en OrderRepository.
 */
class AuthRepository(private val baseUrl: String = ApiConfig.BASE_URL) {

    suspend fun iniciarSesion(usuario: String, contrasena: String): ResultadoLogin =
        post("login.php", JSONObject().put("usuario", usuario).put("contrasena", contrasena)) { code, _ ->
            when (code) {
                400, 401 -> ResultadoLogin.Rechazado
                // 404, 500...: no se muestra el código ni el cuerpo al usuario
                else -> ResultadoLogin.Fallo(ValidadorAcceso.MSG_ERROR_GENERAL)
            }
        }

    /** Crea la cuenta en registro.php; si sale bien, la app entra con esa cuenta. */
    suspend fun registrar(nombre: String, usuario: String, contrasena: String): ResultadoLogin =
        post("registro.php", JSONObject().put("nombre", nombre).put("usuario", usuario).put("contrasena", contrasena)) { code, body ->
            when (code) {
                // 400 (datos) y 409 (usuario ocupado) traen un mensaje pensado para mostrarse
                400, 409 -> ResultadoLogin.Fallo(mensajeDelServidor(body) ?: ValidadorAcceso.MSG_DATOS_INVALIDOS)
                else -> ResultadoLogin.Fallo(ValidadorAcceso.MSG_ERROR_GENERAL)
            }
        }

    /**
     * POST con cuerpo JSON. Las credenciales van en el cuerpo por HTTPS, nunca en la URL.
     * Un 2xx se convierte en sesión; cualquier otro código lo interpreta [siNoOk].
     */
    private suspend fun post(
        endpoint: String,
        json: JSONObject,
        siNoOk: (code: Int, body: String) -> ResultadoLogin
    ): ResultadoLogin = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val cuerpo = json.toString()

            connection = (URL(baseUrl + endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = ApiConfig.TIMEOUT_MS
                readTimeout = ApiConfig.TIMEOUT_MS
                useCaches = false
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            connection.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                .orEmpty()

            if (code in 200..299) {
                val respuesta = JSONObject(body)
                ResultadoLogin.Exito(
                    Sesion(
                        usuario = respuesta.getString("usuario"),
                        nombre = respuesta.getString("nombre"),
                        rol = respuesta.optString("rol", "cliente"),
                        token = respuesta.optString("token", "")
                    )
                )
            } else {
                siNoOk(code, body)
            }
        } catch (e: JSONException) {
            ResultadoLogin.Fallo(ValidadorAcceso.MSG_ERROR_GENERAL)
        } catch (e: IOException) {
            // Sin internet, servidor caído, tiempo agotado: un solo mensaje sin la excepción
            ResultadoLogin.Fallo(ValidadorAcceso.MSG_SIN_CONEXION)
        } finally {
            connection?.disconnect()
        }
    }

    private fun mensajeDelServidor(body: String): String? = try {
        JSONObject(body).optString("message").takeIf { it.isNotBlank() }
    } catch (e: JSONException) {
        null
    }
}
