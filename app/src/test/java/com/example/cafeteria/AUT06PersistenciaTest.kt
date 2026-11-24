package com.example.cafeteria

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AUT-06 · Prueba de PERSISTENCIA · Room (SQLite local) y carrito en SharedPreferences.
 *
 * Se ejecutan en la JVM con Robolectric (SQLite y SharedPreferences reales de Android, sin
 * emulador), por eso también corren en GitHub Actions.
 *
 * Riesgos que previenen:
 *  - Que la migración 1→2 (columna correo) borre los usuarios que ya estaban guardados.
 *  - Que un carrito guardado corrupto vuelva a cerrar la app al iniciar (defecto corregido antes).
 */
@RunWith(RobolectricTestRunner::class)
class AUT06PersistenciaTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: UsuarioDao

    @Before
    fun preparar() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.usuarioDao()
    }

    @After
    fun cerrar() {
        db.close()
    }

    // ---------- Room: operaciones CRUD ----------

    @Test
    fun registrar_guarda_el_usuario_y_asigna_id() = runBlocking {
        val id = dao.insertar(Usuario(nombre = "Ana López", edad = 20, profesion = "TI", correo = "ana@uteq.mx"))
        assertTrue(id > 0)

        val guardados = dao.obtenerTodos()
        assertEquals(1, guardados.size)
        assertEquals(Usuario(id.toInt(), "Ana López", 20, "TI", "ana@uteq.mx"), guardados[0])
    }

    @Test
    fun modificar_y_eliminar_se_reflejan_en_la_base() = runBlocking {
        val id = dao.insertar(Usuario(nombre = "Luis", edad = 21, profesion = "Redes")).toInt()
        dao.insertar(Usuario(nombre = "Sofía", edad = 22, profesion = "TI"))

        dao.actualizar(Usuario(id, "Luis Pérez", 22, "Redes", "luis@uteq.mx"))
        assertEquals("Luis Pérez", dao.obtenerTodos().first { it.id == id }.nombre)

        dao.eliminar(Usuario(id, "Luis Pérez", 22, "Redes", "luis@uteq.mx"))
        assertEquals(listOf("Sofía"), dao.obtenerTodos().map { it.nombre })
    }

    @Test
    fun consulta_devuelve_en_orden_de_registro_y_correo_por_omision_vacio() = runBlocking {
        listOf("Ana", "Beto", "Carla").forEach { dao.insertar(Usuario(nombre = it, edad = 20, profesion = "TI")) }
        val todos = dao.obtenerTodos()
        assertEquals(listOf("Ana", "Beto", "Carla"), todos.map { it.nombre })
        assertTrue(todos.all { it.correo == "" })
    }

    @Test
    fun migracion_1_a_2_conserva_los_usuarios_existentes() = runBlocking {
        val nombre = "migracion_aut06.db"
        context.deleteDatabase(nombre)

        // Base como la dejaba la versión 1 de la app (sin columna correo)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(nombre).apply { parentFile?.mkdirs() }, null).use { v1 ->
            v1.execSQL("CREATE TABLE usuarios (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, nombre TEXT NOT NULL, edad INTEGER NOT NULL, profesion TEXT NOT NULL)")
            v1.execSQL("INSERT INTO usuarios (nombre, edad, profesion) VALUES ('Marco', 23, 'Software')")
            v1.version = 1
        }

        // La app actual abre esa base con la migración registrada
        val migrada = Room.databaseBuilder(context, AppDatabase::class.java, nombre)
            .addMigrations(AppDatabase.MIGRACION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val usuarios = migrada.usuarioDao().obtenerTodos()
            assertEquals(1, usuarios.size)
            assertEquals("Marco", usuarios[0].nombre)
            assertEquals("", usuarios[0].correo)
        } finally {
            migrada.close()
            context.deleteDatabase(nombre)
        }
    }

    // ---------- Carrito (SharedPreferences) ----------

    private val americano = Product("1", "Americano", "12 oz", 35.0, "")
    private val pan = Product("3", "Pan de elote", "", 27.9, "")

    @Test
    fun el_carrito_se_conserva_al_cerrar_y_abrir_la_app() {
        val antes = CartViewModel(context)
        antes.clearCart()
        antes.addToCart(americano)
        antes.addToCart(americano)
        antes.addToCart(pan)

        // Un ViewModel nuevo equivale a volver a abrir la app: lee lo guardado
        val despues = CartViewModel(context).cartItems.value
        assertEquals(listOf("1" to 2, "3" to 1), despues.map { it.product.id to it.quantity })
        assertEquals(35.0, despues[0].product.price, 0.0)

        CartViewModel(context).removeFromCart("1")
        assertEquals(listOf("3"), CartViewModel(context).cartItems.value.map { it.product.id })
    }

    @Test
    fun carrito_guardado_corrupto_se_descarta_sin_cerrar_la_app() {
        val prefs = context.getSharedPreferences("cart_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("cart_data", "{esto no es json").commit()

        val carrito = CartViewModel(context)   // antes lanzaba JSONException al iniciar

        assertTrue(carrito.cartItems.value.isEmpty())
        assertEquals(null, prefs.getString("cart_data", null))
    }
}
