package com.example.cafeteria

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

/**
 * Práctica 12 · Reintento controlado con espera progresiva (exponential backoff + jitter).
 *
 * Solo se reintenta un [ApiException] marcado como [ApiException.reintentable]
 * (sin red, timeout, servidor ocupado 5xx). Un 4xx (datos inválidos, sesión vencida,
 * sin permiso) se reporta de inmediato: repetirlo daría el mismo resultado.
 *
 * Espera antes del intento n+1 = min(base · 2^(n-1), máximo) ± 25 % al azar:
 *   500 ms → 1 000 ms → 2 000 ms … El azar evita que muchos teléfonos que fallaron
 *   al mismo tiempo (servidor saturado) vuelvan a llegar todos juntos.
 *
 * Las escrituras solo se reintentan si son idempotentes (pedidos.php con Idempotency-Key):
 * así un reintento después de perder la respuesta nunca crea un segundo registro.
 */
class PoliticaReintentos(
    val intentos: Int = 3,
    val esperaBaseMs: Long = 500,
    val esperaMaximaMs: Long = 4_000,
    private val azar: () -> Double = { Random.nextDouble() },
    private val dormir: suspend (Long) -> Unit = { delay(it) }
) {
    init {
        require(intentos >= 1) { "Debe haber al menos un intento" }
    }

    /** Espera (ms) después de que falló el intento número [intento] (1, 2, …). */
    fun esperaDespuesDe(intento: Int): Long {
        val exponencial = esperaBaseMs shl (intento - 1).coerceIn(0, 20)
        val tope = min(exponencial, esperaMaximaMs)
        val factor = 0.75 + azar() * 0.5           // entre 0.75 y 1.25
        return (tope * factor).toLong()
    }

    /**
     * Ejecuta [bloque] y lo repite mientras falle con un error reintentable, hasta [intentos].
     * [alReintentar] avisa a la interfaz (intento que viene, total y espera).
     */
    suspend fun <T> ejecutar(
        alReintentar: (siguiente: Int, total: Int, esperaMs: Long) -> Unit = { _, _, _ -> },
        bloque: suspend (intento: Int) -> T
    ): T {
        var intento = 1
        while (true) {
            try {
                return bloque(intento)
            } catch (e: ApiException) {
                if (!e.reintentable || intento >= intentos) throw e
                val espera = esperaDespuesDe(intento)
                alReintentar(intento + 1, intentos, espera)
                dormir(espera)
                intento++
            }
        }
    }

    companion object {
        /** Sin reintentos: un solo intento (operaciones no idempotentes y pruebas). */
        val SIN_REINTENTOS = PoliticaReintentos(intentos = 1)
    }
}
