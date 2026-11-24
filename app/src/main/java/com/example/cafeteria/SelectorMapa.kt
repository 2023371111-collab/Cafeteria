package com.example.cafeteria

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView

/** Centro por defecto del mapa: Universidad Tecnológica de Querétaro (aprox.). */
private val CAMPUS = GeoPoint(20.6545, -100.4045)

/**
 * Mapa de OpenStreetMap a pantalla completa para elegir el punto de entrega.
 * El pin queda fijo al centro: el usuario mueve el mapa y se toma el centro.
 */
@Composable
fun SelectorMapaDialog(
    inicial: Ubicacion?,
    onElegir: (latitud: Double, longitud: Double) -> Unit,
    onCancelar: () -> Unit
) {
    val context = LocalContext.current

    val mapa = remember {
        // OpenStreetMap pide que cada app se identifique al descargar el mapa
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = context.packageName
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(18.0)
            controller.setCenter(inicial?.let { GeoPoint(it.latitud, it.longitud) } ?: CAMPUS)
        }
    }
    DisposableEffect(mapa) {
        mapa.onResume()
        onDispose {
            mapa.onPause()
            mapa.onDetach()
        }
    }

    Dialog(onDismissRequest = onCancelar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column {
                Text(
                    "Mueve el mapa hasta que el pin quede en tu lugar",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(factory = { mapa }, modifier = Modifier.fillMaxSize())
                    // La punta del pin marca el centro exacto del mapa
                    Text(
                        "📍",
                        fontSize = 40.sp,
                        modifier = Modifier.align(Alignment.Center).offset(y = (-24).dp)
                    )
                }
                Text(
                    "© OpenStreetMap contributors",
                    fontSize = 10.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onCancelar, modifier = Modifier.weight(1f)) {
                        Text("Cancelar")
                    }
                    Button(
                        onClick = {
                            val centro = mapa.mapCenter
                            onElegir(centro.latitude, centro.longitude)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Usar este punto")
                    }
                }
            }
        }
    }
}
