package com.example.cafeteria

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tabla "usuarios" guardada en SQLite (Room) dentro del teléfono.
 *
 * Caso 4: el campo [correo] se agregó después. Al cambiar esta clase también se
 * modificaron la base de datos (versión 2 + migración), el formulario, la inserción,
 * la consulta y la actualización.
 */
@Entity(tableName = "usuarios")
data class Usuario(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val nombre: String,
    val edad: Int,
    /** Carrera del estudiante. */
    val profesion: String,
    @ColumnInfo(defaultValue = "") val correo: String = ""
)
