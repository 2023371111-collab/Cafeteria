package com.example.cafeteria

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Coordenadas que se adjuntan a un pedido para entregarlo dentro del campus. */
data class Ubicacion(
    val latitud: Double,
    val longitud: Double,
    /** Solo cuando viene del GPS/red; null si se eligió en el mapa. */
    val precisionMetros: Float? = null,
    /** Dirección legible obtenida con Geocoder; null mientras se busca o si no hay servicio. */
    val direccion: String? = null
)

/**
 * Obtiene la ubicación actual UNA sola vez, cuando el usuario lo pide.
 * No rastrea en segundo plano ni guarda un historial.
 * Usa LocationManager de Android (no necesita Google Play Services).
 */
class UbicacionProvider(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** true si el usuario dio permiso de ubicación precisa O aproximada. */
    fun tienePermiso(): Boolean = tiene(Manifest.permission.ACCESS_FINE_LOCATION) ||
        tiene(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** true si la ubicación (GPS o red) está encendida en el teléfono. */
    fun ubicacionActivada(): Boolean =
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    /** Devuelve la ubicación actual, o null si no hay permiso, señal o se agotó el tiempo. */
    suspend fun obtenerActual(): Ubicacion? {
        if (!tienePermiso()) return null
        return try {
            val location = elegirProveedor()?.let { proveedor ->
                withTimeoutOrNull(TIMEOUT_MS) { pedirUnaVez(proveedor) }
            } ?: ultimaConocida()
            location?.let { Ubicacion(it.latitude, it.longitude, it.accuracy) }
        } catch (e: SecurityException) {
            null // el usuario quitó el permiso mientras se buscaba
        }
    }

    /**
     * Convierte coordenadas en una dirección legible (geocodificación inversa).
     * Devuelve null si el teléfono no tiene el servicio o no hay internet.
     */
    suspend fun nombreDelLugar(latitud: Double, longitud: Double): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.forLanguageTag("es-MX"))
        return try {
            val direccion = withTimeoutOrNull(TIMEOUT_MS) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocation(latitud, longitud, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(direcciones: MutableList<Address>) {
                                if (cont.isActive) cont.resume(direcciones.firstOrNull())
                            }
                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        })
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocation(latitud, longitud, 1)?.firstOrNull()
                    }
                }
            }
            direccion?.getAddressLine(0)?.take(ValidadorPedido.MAX_DIRECCION)
        } catch (e: Exception) {
            null // IOException: sin red o el servicio no respondió
        }
    }

    /** GPS solo si hay permiso preciso; si no, la red (wifi/antenas) basta para la ubicación aproximada. */
    private fun elegirProveedor(): String? = when {
        tiene(Manifest.permission.ACCESS_FINE_LOCATION) &&
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        else -> null
    }

    @SuppressLint("MissingPermission") // se revisa con tienePermiso() antes de llamar
    private suspend fun pedirUnaVez(proveedor: String): Location? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancelar = CancellationSignal()
            cont.invokeOnCancellation { cancelar.cancel() }
            locationManager.getCurrentLocation(proveedor, cancelar, context.mainExecutor) { location: Location? ->
                if (cont.isActive) cont.resume(location)
            }
        } else {
            // Android 7 a 10: una sola lectura y se deja de escuchar
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (cont.isActive) cont.resume(location)
                }
                @Deprecated("Requerido en Android < 10")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            cont.invokeOnCancellation { locationManager.removeUpdates(listener) }
            @Suppress("DEPRECATION")
            locationManager.requestSingleUpdate(proveedor, listener, Looper.getMainLooper())
        }
    }

    /** Si no llegó una lectura nueva a tiempo, se usa la más reciente que conozca el teléfono. */
    @SuppressLint("MissingPermission")
    private fun ultimaConocida(): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { proveedor ->
                try {
                    locationManager.getLastKnownLocation(proveedor)
                } catch (e: Exception) {
                    null
                }
            }
            .maxByOrNull { it.time }

    private fun tiene(permiso: String): Boolean =
        ContextCompat.checkSelfPermission(context, permiso) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TIMEOUT_MS = 15_000L
    }
}
