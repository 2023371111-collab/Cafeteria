package com.example.cafeteria

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Base de datos local "cafeteria.db".
 * Versión 1: usuarios(id, nombre, edad, profesion).
 * Versión 2: se agrega la columna correo (Caso 4).
 */
@Database(entities = [Usuario::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun usuarioDao(): UsuarioDao

    companion object {
        /** Agrega la columna nueva sin borrar los usuarios que ya estaban guardados. */
        val MIGRACION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usuarios ADD COLUMN correo TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile private var instancia: AppDatabase? = null

        /** Una sola instancia para toda la app (abrir SQLite es costoso). */
        fun obtener(context: Context): AppDatabase =
            instancia ?: synchronized(this) {
                instancia ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "cafeteria.db")
                    .addMigrations(MIGRACION_1_2)
                    .build()
                    .also { instancia = it }
            }
    }
}
