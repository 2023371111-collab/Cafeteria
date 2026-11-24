package com.example.cafeteria

import android.os.SystemClock
import android.util.Log

/**
 * Práctica 12 (profiling): mide cuánto tarda una operación crítica y lo escribe en Logcat
 * con la etiqueta "Perf". Se lee con:  adb logcat -s Perf
 * Formato: "operacion=<nombre> ms=<duración> resultado=<ok|error:Tipo>"
 */
object Perf {
    const val TAG = "Perf"

    inline fun <T> medir(operacion: String, bloque: () -> T): T {
        val inicio = SystemClock.elapsedRealtime()
        try {
            val r = bloque()
            Log.i(TAG, "operacion=$operacion ms=${SystemClock.elapsedRealtime() - inicio} resultado=ok")
            return r
        } catch (e: Throwable) {
            Log.i(TAG, "operacion=$operacion ms=${SystemClock.elapsedRealtime() - inicio} resultado=error:${e.javaClass.simpleName}")
            throw e
        }
    }
}
