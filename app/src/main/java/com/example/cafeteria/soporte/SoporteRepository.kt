package com.example.cafeteria.soporte

import android.content.Context
import android.os.Build
import android.util.Base64
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.FirebaseNetworkException
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.Date

// =============================================
// Acceso a Firebase del módulo "Soporte".
// Base de datos COMPARTIDA: todos los dispositivos leen y escriben el mismo proyecto
// de Firebase (cafeteria-movil) y las mismas colecciones (incidencias, usuarios...).
// =============================================

/** Error con un mensaje listo para mostrarse. [codigo] = código de Firestore/Auth para la evidencia. */
class SoporteException(message: String, val codigo: String = "", cause: Throwable? = null) : Exception(message, cause)

/** Resultado de guardar un cambio con control de concurrencia (práctica 9, fase 4). */
sealed interface ResultadoGuardado {
    data class Guardado(val version: Long) : ResultadoGuardado
    /** Otro dispositivo cambió los MISMOS campos después de que se abrió el registro. */
    data class Conflicto(val campos: Map<String, Pair<Any?, Any?>>, val versionServidor: Long) : ResultadoGuardado
    data object NoExiste : ResultadoGuardado
}

/** Si la app se compiló con -PfirebaseEmulador=HOST usa los emuladores locales en vez de la nube. */
object FirebaseEntorno {
    val emulador: String = com.example.cafeteria.BuildConfig.FIREBASE_EMULADOR
    private val configurados = mutableSetOf<String>()

    @Synchronized
    fun configurar(app: FirebaseApp): Pair<FirebaseAuth, FirebaseFirestore> {
        val auth = FirebaseAuth.getInstance(app)
        val db = FirebaseFirestore.getInstance(app)
        if (emulador.isNotBlank() && configurados.add(app.name)) {
            auth.useEmulator(emulador, 9099)
            db.useEmulator(emulador, 8085)
        }
        return auth to db
    }
}

class SoporteRepository(context: Context) {

    private val firebase = FirebaseEntorno.configurar(FirebaseApp.getInstance())
    val auth: FirebaseAuth = firebase.first
    val db: FirebaseFirestore = firebase.second
    private val appContext = context.applicationContext

    /** Proyecto de Firebase al que está conectada la app (evidencia DB-01: el mismo en A y B). */
    val proyecto: String get() = FirebaseApp.getInstance().options.projectId.orEmpty() +
        if (FirebaseEntorno.emulador.isNotBlank()) " (EMULADOR LOCAL)" else ""

    val dispositivo: String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    private val usuarios get() = db.collection("usuarios")
    private val incidencias get() = db.collection("incidencias")
    private val invitaciones get() = db.collection("invitaciones")

    // ---------------------------------------------
    // Autenticación (práctica 10, fase 1)
    // ---------------------------------------------

    suspend fun iniciarSesion(correo: String, contrasena: String) {
        try {
            auth.signInWithEmailAndPassword(correo.trim(), contrasena).await()
        } catch (e: Exception) {
            throw traducirAuth(e)
        }
    }

    /** Crea la cuenta en Firebase Authentication y su perfil con rol "usuario". */
    suspend fun crearCuenta(nombre: String, correo: String, contrasena: String) {
        try {
            val resultado = auth.createUserWithEmailAndPassword(correo.trim(), contrasena).await()
            val uid = resultado.user?.uid ?: throw SoporteException("No se pudo crear la cuenta.")
            crearPerfil(uid, nombre.trim(), correo.trim())
        } catch (e: SoporteException) {
            throw e
        } catch (e: Exception) {
            throw traducirAuth(e)
        }
    }

    /** Si la cuenta se creó desde la consola y aún no tiene perfil, se le crea uno de "usuario". */
    suspend fun asegurarPerfil(): Perfil {
        val usuario = auth.currentUser ?: throw SoporteException("No hay sesión.")
        val doc = try {
            usuarios.document(usuario.uid).get().await()
        } catch (e: Exception) {
            throw traducir(e)
        }
        if (!doc.exists()) {
            val nombre = usuario.email.orEmpty().substringBefore('@').ifBlank { "Usuario" }.take(40)
            crearPerfil(usuario.uid, nombre, usuario.email.orEmpty())
            return Perfil(usuario.uid, nombre, usuario.email.orEmpty(), Rol.USUARIO, dispositivo, true, Date())
        }
        return perfilDe(doc)
    }

    private suspend fun crearPerfil(uid: String, nombre: String, correo: String) {
        try {
            usuarios.document(uid).set(
                mapOf(
                    "nombre" to nombre.ifBlank { "Usuario" }.take(40).padEnd(2, '_'),
                    "correo" to correo,
                    "rol" to Rol.USUARIO.valor,
                    "dispositivo" to dispositivo,
                    "conectado" to true,
                    "ultimaConexion" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: Exception) {
            throw traducir(e)
        }
    }

    fun cerrarSesion() = auth.signOut()

    /** Latido de conexión: "conectado" + hora del servidor. Se manda cada minuto con la app abierta. */
    suspend fun marcarConexion(conectado: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        try {
            usuarios.document(uid).update(
                mapOf(
                    "conectado" to conectado,
                    "ultimaConexion" to FieldValue.serverTimestamp(),
                    "dispositivo" to dispositivo
                )
            ).await()
        } catch (_: Exception) {
            // Sin red no pasa nada: el latido vencido ya indica que está desconectado
        }
    }

    fun escucharPerfil(uid: String, alCambiar: (Perfil?) -> Unit): ListenerRegistration =
        usuarios.document(uid).addSnapshotListener { doc, _ ->
            alCambiar(doc?.takeIf { it.exists() }?.let(::perfilDe))
        }

    suspend fun perfil(uid: String): Perfil? = try {
        usuarios.document(uid).get().await().takeIf { it.exists() }?.let(::perfilDe)
    } catch (_: Exception) {
        null
    }

    /** Solo personal (admin y agente): todas las cuentas, en tiempo real. */
    fun escucharUsuarios(alCambiar: (List<Perfil>) -> Unit, alFallar: (SoporteException) -> Unit): ListenerRegistration =
        usuarios.addSnapshotListener { snap, error ->
            if (error != null) alFallar(traducir(error))
            else if (snap != null) alCambiar(snap.documents.map(::perfilDe).sortedBy { it.nombre.lowercase() })
        }

    suspend fun cambiarRol(uid: String, rol: Rol) {
        try {
            usuarios.document(uid).update("rol", rol.valor).await()
        } catch (e: Exception) {
            throw traducir(e)
        }
    }

    // ---------------------------------------------
    // Invitaciones: URL preasignada de rol (práctica 7)
    // ---------------------------------------------

    /** El admin genera un código de un solo uso, válido 24 h. Regresa la URL para compartir. */
    suspend fun crearInvitacion(rol: Rol): String {
        val uid = auth.currentUser?.uid ?: throw SoporteException("No hay sesión.")
        val alfabeto = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val azar = SecureRandom()
        val codigo = (1..8).map { alfabeto[azar.nextInt(alfabeto.length)] }.joinToString("")
        try {
            invitaciones.document(codigo).set(
                mapOf(
                    "rol" to rol.valor,
                    "creadaPor" to uid,
                    "creadaEn" to FieldValue.serverTimestamp(),
                    "expira" to com.google.firebase.Timestamp(Date(System.currentTimeMillis() + 24 * 3_600_000L)),
                    "usadaPor" to null
                )
            ).await()
        } catch (e: Exception) {
            throw traducir(e)
        }
        return urlInvitacion(codigo)
    }

    /** Toma el rol de la invitación. En un solo batch: marca la invitación usada y cambia el rol. */
    suspend fun aceptarInvitacion(codigo: String): Rol {
        val uid = auth.currentUser?.uid ?: throw SoporteException("Inicia sesión para usar la invitación.")
        val ref = invitaciones.document(codigo.uppercase())
        try {
            val inv = ref.get(Source.SERVER).await()
            if (!inv.exists()) throw SoporteException("La invitación no existe.")
            if (inv.getString("usadaPor") != null) throw SoporteException("La invitación ya fue usada.")
            if ((inv.getTimestamp("expira")?.toDate()?.time ?: 0) < System.currentTimeMillis()) {
                throw SoporteException("La invitación ya venció.")
            }
            val rol = Rol.de(inv.getString("rol"))
            db.batch()
                .update(ref, "usadaPor", uid)
                .update(usuarios.document(uid), mapOf("rol" to rol.valor, "invitacion" to ref.id))
                .commit().await()
            return rol
        } catch (e: SoporteException) {
            throw e
        } catch (e: Exception) {
            throw traducir(e)
        }
    }

    // ---------------------------------------------
    // Incidencias
    // ---------------------------------------------

    /**
     * La consulta depende del rol, porque las reglas NO filtran: si la consulta pudiera traer
     * un documento ajeno, Firestore rechaza la consulta completa.
     */
    fun consultaPara(perfil: Perfil): Query = when (perfil.rol) {
        Rol.ADMIN -> incidencias.whereEqualTo("activo", true)
        Rol.AGENTE -> incidencias.whereEqualTo("activo", true).whereEqualTo("dirigidoA", Catalogos.PARA_SOPORTE)
        Rol.USUARIO -> incidencias.whereEqualTo("activo", true).whereEqualTo("creadoPor", perfil.uid)
    }

    /** Tiempo real (admin y agente): cada cambio en otro dispositivo llega solo. */
    fun escucharIncidencias(
        perfil: Perfil,
        alCambiar: (List<Incidencia>, desdeCache: Boolean) -> Unit,
        alFallar: (SoporteException) -> Unit
    ): ListenerRegistration =
        consultaPara(perfil).addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
            if (error != null) alFallar(traducir(error))
            else if (snap != null) alCambiar(ordenar(snap.documents.map(::incidenciaDe)), snap.metadata.isFromCache)
        }

    /** Consulta manual (usuarios): hay que presionar "Actualizar" para ver cambios de otros. */
    suspend fun consultarIncidencias(perfil: Perfil): Pair<List<Incidencia>, Boolean> = try {
        val snap = consultaPara(perfil).get().await()
        ordenar(snap.documents.map(::incidenciaDe)) to snap.metadata.isFromCache
    } catch (e: Exception) {
        throw traducir(e)
    }

    private fun ordenar(lista: List<Incidencia>) =
        lista.sortedByDescending { it.creadoEn?.time ?: Long.MAX_VALUE }

    fun escucharIncidencia(id: String, alCambiar: (Incidencia?) -> Unit, alFallar: (SoporteException) -> Unit): ListenerRegistration =
        incidencias.document(id).addSnapshotListener(MetadataChanges.INCLUDE) { doc, error ->
            if (error != null) alFallar(traducir(error))
            else alCambiar(doc?.takeIf { it.exists() }?.let(::incidenciaDe))
        }

    /** Buscar por ID (TC06): null si no existe. Lee del servidor para no confundir con la caché. */
    suspend fun buscar(id: String): Incidencia? = try {
        incidencias.document(id).get(Source.SERVER).await().takeIf { it.exists() }?.let(::incidenciaDe)
    } catch (e: Exception) {
        throw traducir(e)
    }

    /**
     * Alta con transacción: si el ID ya existe NO se sobrescribe (TC03).
     * Las transacciones necesitan al servidor: sin conexión fallan en lugar de fingir éxito.
     */
    suspend fun crear(
        perfil: Perfil, id: String, titulo: String, tipo: String, equipo: String,
        descripcion: String, prioridad: String, dirigidoA: String
    ) {
        val ref = incidencias.document(id)
        val creada = try {
            db.runTransaction { tx ->
                if (tx.get(ref).exists()) return@runTransaction false
                tx.set(ref, mapOf(
                    "id" to id,
                    "titulo" to titulo.trim(),
                    "tipo" to tipo,
                    "equipo" to equipo.trim(),
                    "descripcion" to descripcion.trim(),
                    "estado" to "Pendiente",
                    "prioridad" to prioridad,
                    "dirigidoA" to dirigidoA,
                    "creadoPor" to perfil.uid,
                    "creadoPorNombre" to perfil.nombre,
                    "asignadoA" to null,
                    "asignadoNombre" to null,
                    "activo" to true,
                    "version" to 1L,
                    "creadoEn" to FieldValue.serverTimestamp(),
                    "actualizadoEn" to FieldValue.serverTimestamp(),
                    "actualizadoPor" to perfil.uid,
                    "actualizadoPorNombre" to perfil.nombre
                ))
                true
            }.await()
        } catch (e: Exception) {
            throw traducir(e)
        }
        if (!creada) throw SoporteException("Ya existe un reporte con el ID $id. No se sobrescribió; usa otro ID.", "ALREADY_EXISTS")
    }

    /**
     * Guarda SOLO los campos que cambiaron, con transacción (práctica 9, fase 4).
     * Política: se combinan cambios de distintos campos; si otro dispositivo cambió el MISMO
     * campo después de que se abrió el registro, se avisa del conflicto (salvo que se fuerce).
     */
    suspend fun guardarCambios(
        perfil: Perfil, original: Incidencia, cambios: Map<String, Any?>, forzar: Boolean = false
    ): ResultadoGuardado {
        val ref = incidencias.document(original.id)
        return try {
            db.runTransaction { tx ->
                val actual = tx.get(ref)
                if (!actual.exists()) return@runTransaction ResultadoGuardado.NoExiste
                val versionServidor = actual.getLong("version") ?: 0L
                val conflictos = cambios.keys
                    .filter { campo -> actual.get(campo) != valorOriginal(original, campo) }
                    .associateWith { campo -> valorOriginal(original, campo) to actual.get(campo) }
                if (conflictos.isNotEmpty() && !forzar) {
                    return@runTransaction ResultadoGuardado.Conflicto(conflictos, versionServidor)
                }
                tx.update(ref, cambios + mapOf(
                    "version" to versionServidor + 1,
                    "actualizadoEn" to FieldValue.serverTimestamp(),
                    "actualizadoPor" to perfil.uid,
                    "actualizadoPorNombre" to perfil.nombre
                ))
                ResultadoGuardado.Guardado(versionServidor + 1)
            }.await()
        } catch (e: Exception) {
            throw traducir(e)
        }
    }

    /**
     * Sin conexión: se escribe en la caché local del teléfono y Firestore lo manda al volver
     * la red (la lista lo marca "⏳ pendiente de sincronizar"). El ack del servidor llega después.
     */
    fun guardarSinConexion(perfil: Perfil, id: String, cambios: Map<String, Any?>) {
        incidencias.document(id).update(cambios + mapOf(
            "version" to FieldValue.increment(1),
            "actualizadoEn" to FieldValue.serverTimestamp(),
            "actualizadoPor" to perfil.uid,
            "actualizadoPorNombre" to perfil.nombre
        ))
    }

    /** Baja lógica (TC05): deja de aparecer como registro activo, el documento queda de historial. */
    suspend fun eliminar(perfil: Perfil, original: Incidencia): ResultadoGuardado =
        guardarCambios(perfil, original, mapOf("activo" to false), forzar = true)

    /** Al desconectarse un agente sus incidencias abiertas regresan al administrador (práctica 7). */
    suspend fun soltarAsignadas(perfil: Perfil) {
        try {
            val snap = incidencias.whereEqualTo("activo", true)
                .whereEqualTo("dirigidoA", Catalogos.PARA_SOPORTE)
                .whereEqualTo("asignadoA", perfil.uid).get(Source.SERVER).await()
            snap.documents.map(::incidenciaDe).filter { it.estado != "Resuelta" }.forEach { inc ->
                guardarCambios(perfil, inc, mapOf("asignadoA" to null, "asignadoNombre" to null), forzar = true)
            }
        } catch (_: Exception) {
            // Si no hay red, el admin igual lo ve "desconectado" y reasigna a mano
        }
    }

    private fun valorOriginal(inc: Incidencia, campo: String): Any? = when (campo) {
        "titulo" -> inc.titulo
        "equipo" -> inc.equipo
        "descripcion" -> inc.descripcion
        "estado" -> inc.estado
        "prioridad" -> inc.prioridad
        "asignadoA" -> inc.asignadoA
        "asignadoNombre" -> inc.asignadoNombre
        "dirigidoA" -> inc.dirigidoA
        "activo" -> inc.activo
        else -> null
    }

    // ---------------------------------------------
    // Conversación y archivos (práctica 7)
    // ---------------------------------------------

    fun escucharMensajes(
        idIncidencia: String,
        alCambiar: (List<MensajeSoporte>) -> Unit,
        alFallar: (SoporteException) -> Unit
    ): ListenerRegistration =
        incidencias.document(idIncidencia).collection("mensajes")
            .orderBy("enviadoEn", Query.Direction.ASCENDING)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
                if (error != null) alFallar(traducir(error))
                else if (snap != null) alCambiar(snap.documents.map(::mensajeDe))
            }

    /** Mensaje con archivo opcional: archivo y mensaje se guardan juntos en un batch (todo o nada). */
    suspend fun enviarMensaje(perfil: Perfil, idIncidencia: String, para: String, texto: String, adjunto: Adjunto?) {
        val base = incidencias.document(idIncidencia)
        val batch = db.batch()
        val archivoRef = adjunto?.let { base.collection("archivos").document() }
        if (adjunto != null && archivoRef != null) {
            batch.set(archivoRef, mapOf(
                "nombre" to adjunto.nombre,
                "tipo" to adjunto.tipo,
                "bytes" to adjunto.datos.size.toLong(),
                "datos" to Base64.encodeToString(adjunto.datos, Base64.NO_WRAP),
                "subidoPor" to perfil.uid,
                "subidoEn" to FieldValue.serverTimestamp()
            ))
        }
        batch.set(base.collection("mensajes").document(), mapOf(
            "emisorUid" to perfil.uid,
            "emisorNombre" to perfil.nombre,
            "emisorRol" to perfil.rol.valor,
            "emisorDispositivo" to dispositivo,
            "para" to para,
            "texto" to texto,
            "archivoId" to archivoRef?.id,
            "archivoNombre" to adjunto?.nombre,
            "archivoTipo" to adjunto?.tipo,
            "archivoBytes" to (adjunto?.datos?.size?.toLong() ?: 0L),
            "enviadoEn" to FieldValue.serverTimestamp()
        ))
        try {
            batch.commit().await()
        } catch (e: Exception) {
            throw traducir(e)
        }
    }

    suspend fun descargarArchivo(idIncidencia: String, archivoId: String): ByteArray = try {
        val doc = incidencias.document(idIncidencia).collection("archivos").document(archivoId).get().await()
        Base64.decode(doc.getString("datos").orEmpty(), Base64.DEFAULT)
    } catch (e: Exception) {
        throw traducir(e)
    }

    // ---------------------------------------------
    // Conversión de documentos
    // ---------------------------------------------

    private fun perfilDe(doc: DocumentSnapshot) = Perfil(
        uid = doc.id,
        nombre = doc.getString("nombre").orEmpty(),
        correo = doc.getString("correo").orEmpty(),
        rol = Rol.de(doc.getString("rol")),
        dispositivo = doc.getString("dispositivo").orEmpty(),
        conectado = doc.getBoolean("conectado") ?: false,
        ultimaConexion = doc.getTimestamp("ultimaConexion")?.toDate()
    )

    fun incidenciaDe(doc: DocumentSnapshot) = Incidencia(
        id = doc.id,
        titulo = doc.getString("titulo").orEmpty(),
        tipo = doc.getString("tipo").orEmpty(),
        equipo = doc.getString("equipo").orEmpty(),
        descripcion = doc.getString("descripcion").orEmpty(),
        estado = doc.getString("estado").orEmpty(),
        prioridad = doc.getString("prioridad").orEmpty(),
        dirigidoA = doc.getString("dirigidoA") ?: Catalogos.PARA_SOPORTE,
        creadoPor = doc.getString("creadoPor").orEmpty(),
        creadoPorNombre = doc.getString("creadoPorNombre").orEmpty(),
        asignadoA = doc.getString("asignadoA"),
        asignadoNombre = doc.getString("asignadoNombre"),
        activo = doc.getBoolean("activo") ?: true,
        version = doc.getLong("version") ?: 0L,
        creadoEn = doc.getTimestamp("creadoEn")?.toDate(),
        actualizadoEn = doc.getTimestamp("actualizadoEn")?.toDate(),
        actualizadoPor = doc.getString("actualizadoPor"),
        actualizadoPorNombre = doc.getString("actualizadoPorNombre"),
        pendienteDeSincronizar = doc.metadata.hasPendingWrites()
    )

    private fun mensajeDe(doc: DocumentSnapshot) = MensajeSoporte(
        id = doc.id,
        emisorUid = doc.getString("emisorUid").orEmpty(),
        emisorNombre = doc.getString("emisorNombre").orEmpty(),
        emisorRol = Rol.de(doc.getString("emisorRol")),
        emisorDispositivo = doc.getString("emisorDispositivo").orEmpty(),
        para = doc.getString("para") ?: "todos",
        texto = doc.getString("texto").orEmpty(),
        archivoId = doc.getString("archivoId"),
        archivoNombre = doc.getString("archivoNombre"),
        archivoTipo = doc.getString("archivoTipo"),
        archivoBytes = doc.getLong("archivoBytes") ?: 0L,
        // Mientras el servidor no confirma, la hora del servidor aún no existe: se estima
        enviadoEn = doc.getTimestamp("enviadoEn", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate(),
        pendiente = doc.metadata.hasPendingWrites()
    )

    companion object {
        fun urlInvitacion(codigo: String) = "cafeteria://rol?codigo=$codigo"

        /** Traduce errores de Firestore: distingue la falta de red de una negación por reglas. */
        fun traducir(e: Throwable): SoporteException {
            if (e is SoporteException) return e
            val fs = (e as? FirebaseFirestoreException) ?: (e.cause as? FirebaseFirestoreException)
            return when (fs?.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    SoporteException("🔒 Firestore rechazó la operación: no tienes permiso (PERMISSION_DENIED).", "PERMISSION_DENIED", e)
                FirebaseFirestoreException.Code.UNAVAILABLE ->
                    SoporteException("📡 Sin conexión con Firestore: la operación NO se confirmó (UNAVAILABLE).", "UNAVAILABLE", e)
                FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                    SoporteException("🔒 Debes iniciar sesión (UNAUTHENTICATED).", "UNAUTHENTICATED", e)
                FirebaseFirestoreException.Code.FAILED_PRECONDITION ->
                    SoporteException("⚠️ Falta configurar Firestore o un índice (FAILED_PRECONDITION).", "FAILED_PRECONDITION", e)
                FirebaseFirestoreException.Code.NOT_FOUND ->
                    SoporteException("El documento no existe (NOT_FOUND).", "NOT_FOUND", e)
                null -> if (e is FirebaseNetworkException) SoporteException("📡 Sin conexión a internet.", "RED", e)
                        else SoporteException("Ocurrió un error inesperado.", e.javaClass.simpleName, e)
                else -> SoporteException("Error de Firestore (${fs.code.name}).", fs.code.name, e)
            }
        }

        /** Mensajes genéricos de login: no dicen si el correo existe o la contraseña falló. */
        fun traducirAuth(e: Throwable): SoporteException = when (e) {
            is SoporteException -> e
            is FirebaseAuthInvalidCredentialsException, is FirebaseAuthInvalidUserException ->
                SoporteException("Correo o contraseña incorrectos.", (e as FirebaseAuthException).errorCode, e)
            is FirebaseAuthUserCollisionException -> SoporteException("Ya existe una cuenta con ese correo.", e.errorCode, e)
            is FirebaseAuthWeakPasswordException -> SoporteException("La contraseña es muy débil (mínimo 6 caracteres).", e.errorCode, e)
            is FirebaseNetworkException -> SoporteException("📡 Sin conexión a internet.", "RED", e)
            is FirebaseAuthException -> when (e.errorCode) {
                "ERROR_OPERATION_NOT_ALLOWED" -> SoporteException("Falta habilitar Correo/Contraseña en Firebase Authentication.", e.errorCode, e)
                "ERROR_TOO_MANY_REQUESTS" -> SoporteException("Demasiados intentos. Espera un momento.", e.errorCode, e)
                else -> SoporteException("No fue posible iniciar sesión.", e.errorCode, e)
            }
            else -> SoporteException("No fue posible conectar con Firebase.", e.javaClass.simpleName, e)
        }
    }
}
