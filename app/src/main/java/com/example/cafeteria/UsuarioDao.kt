package com.example.cafeteria

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Operaciones CRUD sobre la tabla "usuarios". Room genera el SQL de cada una. */
@Dao
interface UsuarioDao {

    /** Caso 1: registrar. Devuelve el id que SQLite le asignó. */
    @Insert
    suspend fun insertar(usuario: Usuario): Long

    /** Caso 2: consultar. Es la que se ejecuta al presionar "VER usuarios". */
    @Query("SELECT * FROM usuarios ORDER BY id ASC")
    suspend fun obtenerTodos(): List<Usuario>

    /** Caso 3: modificar (busca el registro por su id). */
    @Update
    suspend fun actualizar(usuario: Usuario)

    @Delete
    suspend fun eliminar(usuario: Usuario)
}
