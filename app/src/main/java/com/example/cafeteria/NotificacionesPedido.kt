package com.example.cafeteria

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

// =============================================
// Aviso de "pedido listo":
//   barista.php → Firebase Cloud Messaging → este teléfono → reloj Wear OS emparejado
// =============================================

/** Lo que llega en el mensaje de datos de FCM (lo arma barista.php). */
data class AvisoPedido(val folio: String, val titulo: String, val mensaje: String)

object NotificacionesPedido {

    private const val TAG = "PedidoListo"
    private const val CANAL_ID = "pedido_listo"

    /** Ruta del mensaje en la Data Layer; el módulo :wear escucha la misma. */
    const val RUTA_RELOJ = "/pedido-listo"

    /**
     * Token FCM de este teléfono para mandarlo con el pedido.
     * null si Firebase no está configurado (falta google-services.json) o no responde:
     * el pedido se envía igual, solo que sin aviso.
     */
    suspend fun tokenFcm(): String? = try {
        withTimeoutOrNull(5_000) { FirebaseMessaging.getInstance().token.await() }
    } catch (e: Exception) {
        Log.w(TAG, "Firebase no disponible: ${e.javaClass.simpleName}")
        null
    }

    fun tienePermiso(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Notificación en el teléfono. */
    @SuppressLint("MissingPermission") // el permiso se revisa con tienePermiso()
    fun mostrarEnTelefono(context: Context, aviso: AvisoPedido) {
        if (!tienePermiso(context)) return
        crearCanal(context)

        val abrirApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notificacion = NotificationCompat.Builder(context, CANAL_ID)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(aviso.titulo)
            .setContentText(aviso.mensaje)
            .setStyle(NotificationCompat.BigTextStyle().bigText(aviso.mensaje))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(abrirApp)
            .setAutoCancel(true)
            // El reloj muestra su propia notificación (módulo :wear); sin esto Android
            // también copiaría esta al reloj y llegarían dos.
            .setLocalOnly(true)
            .build()
        NotificationManagerCompat.from(context).notify(aviso.folio.hashCode(), notificacion)
    }

    /**
     * Reenvía el aviso a cada reloj conectado por la Data Layer de Wear OS.
     * Regresa cuántos relojes lo recibieron (0 si no hay reloj emparejado).
     */
    suspend fun enviarAlReloj(context: Context, aviso: AvisoPedido): Int {
        val datos = JSONObject()
            .put("folio", aviso.folio)
            .put("titulo", aviso.titulo)
            .put("mensaje", aviso.mensaje)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val relojes = Wearable.getNodeClient(context).connectedNodes.await()
        val mensajes = Wearable.getMessageClient(context)
        var entregados = 0
        relojes.forEach { reloj ->
            try {
                mensajes.sendMessage(reloj.id, RUTA_RELOJ, datos).await()
                entregados++
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo enviar al reloj ${reloj.displayName}: ${e.javaClass.simpleName}")
            }
        }
        Log.i(TAG, "Aviso ${aviso.folio} enviado a $entregados de ${relojes.size} reloj(es)")
        return entregados
    }

    private fun crearCanal(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val canal = NotificationChannel(CANAL_ID, "Pedido listo", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Aviso cuando tu pedido está listo para recoger"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }
}

/** Recibe los mensajes de Firebase aunque la app esté cerrada. */
class CafeteriaMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(mensaje: RemoteMessage) {
        val datos = mensaje.data
        if (datos["tipo"] != "pedido_listo") return
        val aviso = AvisoPedido(
            folio = datos["folio"].orEmpty(),
            titulo = datos["titulo"] ?: "¡Tu pedido está listo!",
            mensaje = datos["mensaje"] ?: "Pasa al mostrador."
        )

        NotificacionesPedido.mostrarEnTelefono(this, aviso)
        // onMessageReceived ya corre en un hilo de fondo y tiene unos segundos para terminar
        runBlocking {
            withTimeoutOrNull(8_000) {
                try {
                    NotificacionesPedido.enviarAlReloj(this@CafeteriaMessagingService, aviso)
                } catch (e: Exception) {
                    Log.w("PedidoListo", "Sin reloj disponible: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        // El token se manda con cada pedido nuevo (OrdersViewModel), no hace falta guardarlo aquí
    }
}
