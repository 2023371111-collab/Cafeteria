package com.example.cafeteria.wear

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
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONException
import org.json.JSONObject

/**
 * Recibe el aviso que reenvía el teléfono (NotificacionesPedido.enviarAlReloj)
 * por la Data Layer: vibra y muestra la notificación en la muñeca.
 * Android lo despierta aunque la app del reloj esté cerrada.
 */
class PedidoListoService : WearableListenerService() {

    override fun onMessageReceived(evento: MessageEvent) {
        if (evento.path != RUTA) return
        val aviso = try {
            JSONObject(String(evento.data, Charsets.UTF_8))
        } catch (e: JSONException) {
            Log.w(TAG, "Aviso con formato inválido")
            return
        }
        val folio = aviso.optString("folio")
        val titulo = aviso.optString("titulo", "¡Tu pedido está listo!")
        val mensaje = aviso.optString("mensaje", "Pasa al mostrador.")

        // Lo último que llegó se muestra al abrir la app del reloj
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("folio", folio)
            .putString("titulo", titulo)
            .putString("mensaje", mensaje)
            .putLong("recibido", System.currentTimeMillis())
            .apply()

        mostrar(folio, titulo, mensaje)
        Log.i(TAG, "Aviso $folio recibido en el reloj")
    }

    @SuppressLint("MissingPermission") // el permiso se revisa en la línea de abajo
    private fun mostrar(folio: String, titulo: String, mensaje: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Sin permiso de notificaciones en el reloj: abre la app Cafetería en el reloj para darlo")
            return
        }
        crearCanal()

        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, RelojActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notificacion = NotificationCompat.Builder(this, CANAL_ID)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(titulo)
            .setContentText(mensaje)
            .setStyle(NotificationCompat.BigTextStyle().bigText(mensaje))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(VIBRACION)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(folio.hashCode(), notificacion)
    }

    private fun crearCanal() {
        val canal = NotificationChannel(CANAL_ID, "Pedido listo", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Vibra cuando tu pedido está listo para recoger"
            enableVibration(true)
            vibrationPattern = VIBRACION
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    companion object {
        private const val TAG = "PedidoListo"
        /** Misma ruta que NotificacionesPedido.RUTA_RELOJ en la app del teléfono. */
        const val RUTA = "/pedido-listo"
        const val PREFS = "aviso_reloj"
        private const val CANAL_ID = "pedido_listo"
        /** Pausa, vibra, pausa, vibra: dos toques largos para que se sienta en la muñeca. */
        private val VIBRACION = longArrayOf(0, 500, 200, 500)
    }
}
