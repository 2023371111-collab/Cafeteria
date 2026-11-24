package com.example.cafeteria.soporte

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =============================================
// Práctica 10: pruebas de acceso autorizado y NO autorizado.
// Cada prueba hace la operación directamente contra Firestore, sin pasar por las validaciones
// ni los botones de la app. Así se demuestra que el rechazo lo hacen las REGLAS de Firestore
// y no solo la interfaz (pregunta 1 de la práctica).
// En Logcat: filtrar por tag:SeguridadFS
// =============================================

data class ResultadoPrueba(
    val clave: String,
    val accion: String,
    val esperado: String,
    /** true = la operación se completó en el servidor; false = la rechazó. */
    val permitida: Boolean,
    val detalle: String,
    val hora: String
) {
    /** La prueba pasa cuando lo ocurrido coincide con lo esperado. */
    val paso: Boolean get() = (esperado == PERMITIDA) == permitida

    companion object {
        const val PERMITIDA = "Permitida"
        const val RECHAZADA = "Rechazada"
    }
}

class PruebasSeguridad(context: Context, private val repo: SoporteRepository) {

    private val appContext = context.applicationContext
    private val db get() = repo.db
    private val hora get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    /**
     * Firestore de una segunda instancia de Firebase SIN sesión (como un cliente que nunca
     * inició sesión o la cerró). Comparte el proyecto pero no el usuario autenticado.
     */
    private val dbSinSesion: FirebaseFirestore by lazy {
        val app = FirebaseApp.getApps(appContext).firstOrNull { it.name == "sin-sesion" }
            ?: FirebaseApp.initializeApp(appContext, FirebaseApp.getInstance().options, "sin-sesion")
        FirebaseEntorno.configurar(app).second
    }

    private val uid get() = repo.auth.currentUser?.uid.orEmpty()

    private suspend fun ejecutar(
        clave: String, accion: String, esperado: String, operacion: suspend () -> String
    ): ResultadoPrueba {
        val resultado = try {
            val ok = operacion()
            ResultadoPrueba(clave, accion, esperado, true, "✅ El servidor aceptó la operación. $ok", hora)
        } catch (e: Exception) {
            val error = SoporteRepository.traducir(e)
            ResultadoPrueba(clave, accion, esperado, false, "${error.message} [${error.codigo}]", hora)
        }
        // Solo el evento y el resultado: nunca contraseñas
        Log.i(TAG, "$clave ${if (resultado.permitida) "PERMITIDA" else "RECHAZADA"} (esperado: $esperado) → ${resultado.detalle}")
        return resultado
    }

    /** SEG-03: crear una incidencia propia (creadoPor = mi UID). */
    suspend fun crearPropia(perfil: Perfil): ResultadoPrueba = ejecutar(
        "SEG-03", "Crear incidencia con mi UID", ResultadoPrueba.PERMITIDA
    ) {
        val id = "SEG03-" + System.currentTimeMillis().toString().takeLast(6)
        db.collection("incidencias").document(id).set(docIncidencia(id, perfil.uid, perfil.nombre)).await()
        "Documento $id con creadoPor = ${perfil.uid.take(8)}…"
    }

    /** SEG-04: suplantación. Crear una incidencia diciendo que la creó otro UID. */
    suspend fun suplantarPropietario(uidAjeno: String, nombre: String): ResultadoPrueba = ejecutar(
        "SEG-04", "Crear incidencia con creadoPor = UID ajeno", ResultadoPrueba.RECHAZADA
    ) {
        val id = "SEG04-" + System.currentTimeMillis().toString().takeLast(6)
        db.collection("incidencias").document(id).set(docIncidencia(id, uidAjeno, nombre)).await()
        "Se creó $id a nombre de otro"
    }

    /** SEG-05: leer directamente (por su ID) la incidencia de otra persona, en el servidor. */
    suspend fun leerAjena(idAjena: String): ResultadoPrueba = ejecutar(
        "SEG-05", "Leer incidencia ajena $idAjena", ResultadoPrueba.RECHAZADA
    ) {
        val doc = db.collection("incidencias").document(idAjena).get(Source.SERVER).await()
        if (doc.exists()) "Se leyó: \"${doc.getString("titulo")}\" de ${doc.getString("creadoPorNombre")}"
        else "El documento no existe (escribe un ID que sí exista)"
    }

    /** SEG-06: modificar la incidencia de otra persona. */
    suspend fun modificarAjena(idAjena: String): ResultadoPrueba = ejecutar(
        "SEG-06", "Modificar incidencia ajena $idAjena", ResultadoPrueba.RECHAZADA
    ) {
        db.collection("incidencias").document(idAjena).update(cambio("prioridad", "Crítica")).await()
        "Se cambió la prioridad de $idAjena"
    }

    /** SEG-07: eliminar la incidencia de otra persona (borrado físico y baja lógica). */
    suspend fun eliminarAjena(idAjena: String): ResultadoPrueba = ejecutar(
        "SEG-07", "Eliminar incidencia ajena $idAjena", ResultadoPrueba.RECHAZADA
    ) {
        val ref = db.collection("incidencias").document(idAjena)
        val fisico = runCatching { ref.delete().await() }
        val logico = runCatching { ref.update(cambio("activo", false)).await() }
        val error = logico.exceptionOrNull() ?: fisico.exceptionOrNull()
        if (fisico.isFailure && logico.isFailure) throw error!!
        "Borrado físico: ${if (fisico.isSuccess) "SÍ" else "no"} · baja lógica: ${if (logico.isSuccess) "SÍ" else "no"}"
    }

    /** SEG-08: cambiar el estado de mi propia incidencia. */
    suspend fun actualizarPropia(idPropia: String): ResultadoPrueba = ejecutar(
        "SEG-08", "Cambiar estado de mi incidencia $idPropia", ResultadoPrueba.PERMITIDA
    ) {
        db.collection("incidencias").document(idPropia).update(cambio("estado", "En proceso")).await()
        "Estado de $idPropia → En proceso"
    }

    /** SEG-09: pasarle mi incidencia a otro dueño. */
    suspend fun cambiarPropietario(idPropia: String, uidAjeno: String): ResultadoPrueba = ejecutar(
        "SEG-09", "Cambiar creadoPor de $idPropia a UID ajeno", ResultadoPrueba.RECHAZADA
    ) {
        db.collection("incidencias").document(idPropia).update(cambio("creadoPor", uidAjeno)).await()
        "El propietario de $idPropia cambió"
    }

    /** SEG-10: leer y escribir sin sesión (instancia de Firebase sin usuario autenticado). */
    suspend fun sinAutenticacion(): List<ResultadoPrueba> = listOf(
        ejecutar("SEG-10", "Leer incidencias SIN sesión", ResultadoPrueba.RECHAZADA) {
            val snap = dbSinSesion.collection("incidencias").limit(5).get(Source.SERVER).await()
            "Se leyeron ${snap.size()} documentos sin sesión"
        },
        ejecutar("SEG-10", "Escribir incidencia SIN sesión", ResultadoPrueba.RECHAZADA) {
            val id = "SEG10-" + System.currentTimeMillis().toString().takeLast(6)
            dbSinSesion.collection("incidencias").document(id).set(docIncidencia(id, "anonimo", "Anónimo")).await()
            "Se creó $id sin sesión"
        }
    )

    /** Extra (pregunta 5): mandar un campo inesperado y un tipo de dato incorrecto. */
    suspend fun camposInesperados(perfil: Perfil): ResultadoPrueba = ejecutar(
        "EXTRA-1", "Crear incidencia con campo extra \"esAdmin\" y prioridad numérica", ResultadoPrueba.RECHAZADA
    ) {
        val id = "EXT1-" + System.currentTimeMillis().toString().takeLast(6)
        val doc = docIncidencia(id, perfil.uid, perfil.nombre) + mapOf("esAdmin" to true, "prioridad" to 5)
        db.collection("incidencias").document(id).set(doc).await()
        "Se aceptaron campos inesperados"
    }

    /** Extra: un usuario intenta subirse el rol a administrador sin invitación. */
    suspend fun escalarRol(): ResultadoPrueba = ejecutar(
        "EXTRA-2", "Cambiar mi propio rol a admin", ResultadoPrueba.RECHAZADA
    ) {
        db.collection("usuarios").document(uid).update("rol", "admin").await()
        "¡Mi rol ahora es admin!"
    }

    private fun cambio(campo: String, valor: Any) = mapOf(
        campo to valor,
        "version" to FieldValue.increment(1),
        "actualizadoEn" to FieldValue.serverTimestamp(),
        "actualizadoPor" to uid,
        "actualizadoPorNombre" to "Prueba de seguridad"
    )

    private fun docIncidencia(id: String, creadoPor: String, nombre: String) = mapOf(
        "id" to id,
        "titulo" to "Prueba de seguridad $id",
        "tipo" to "Otro",
        "equipo" to "Emulador",
        "descripcion" to "Documento creado por la matriz de pruebas de seguridad.",
        "estado" to "Pendiente",
        "prioridad" to "Baja",
        "dirigidoA" to Catalogos.PARA_SOPORTE,
        "creadoPor" to creadoPor,
        "creadoPorNombre" to nombre,
        "asignadoA" to null,
        "asignadoNombre" to null,
        "activo" to true,
        "version" to 1L,
        "creadoEn" to FieldValue.serverTimestamp(),
        "actualizadoEn" to FieldValue.serverTimestamp(),
        "actualizadoPor" to creadoPor,
        "actualizadoPorNombre" to nombre
    )

    companion object {
        const val TAG = "SeguridadFS"
    }
}
