package com.example.cafeteria.wear

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pantalla de la app en el reloj: muestra el último aviso recibido.
 * Lo importante pasa en PedidoListoService; esta pantalla sirve para dar el
 * permiso de notificaciones (Wear OS 4+) y para ver el aviso otra vez.
 */
class RelojActivity : Activity() {

    private lateinit var titulo: TextView
    private lateinit var mensaje: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Columna centrada con margen amplio: en un reloj redondo las orillas se cortan
        titulo = texto(16f, negrita = true)
        mensaje = texto(13f, negrita = false)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(36, 36, 36, 36)
            addView(texto(28f, negrita = false).apply { text = "☕" })
            addView(titulo)
            addView(mensaje)
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(PedidoListoService.PREFS, Context.MODE_PRIVATE)
        val folio = prefs.getString("folio", null)
        if (folio == null) {
            titulo.text = "Cafetería Móvil"
            mensaje.text = "Esperando tu pedido…\nTe avisaremos aquí cuando esté listo."
        } else {
            val hora = DateFormat.getTimeFormat(this).format(prefs.getLong("recibido", 0))
            titulo.text = prefs.getString("titulo", "¡Tu pedido está listo!")
            mensaje.text = "${prefs.getString("mensaje", "")}\n\n$folio · $hora"
        }
    }

    private fun texto(tamano: Float, negrita: Boolean) = TextView(this).apply {
        textSize = tamano
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        if (negrita) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 6, 0, 6)
    }
}
