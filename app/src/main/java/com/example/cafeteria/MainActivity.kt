package com.example.cafeteria

import android.content.Intent
import android.os.Bundle
import com.example.cafeteria.soporte.SoporteScreen
import com.example.cafeteria.soporte.SoporteViewModel
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput

import java.util.Locale

fun formatPrice(value: Double): String = String.format(Locale.US, "$%.2f", value)

class MainActivity : ComponentActivity() {
    private val productsViewModel: ProductsViewModel by viewModels()
    private val ordersViewModel: OrdersViewModel by viewModels()
    private val usuariosViewModel: UsuariosViewModel by viewModels()
    private val consultaWebViewModel: ConsultaWebViewModel by viewModels()
    private val sesionViewModel: SesionViewModel by viewModels()
    private val baristaViewModel: BaristaViewModel by viewModels()
    private val chatViewModel: ChatViewModel by viewModels()
    private val soporteViewModel: SoporteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cartViewModel = CartViewModel(this)
        recibirUrlPreasignada(intent)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                // Práctica 5 (AccesoSeguro): sin sesión no se puede entrar a la app.
                // Validaciones del login (detalle en ValidadorAcceso.kt y SesionViewModel.kt):
                //  1. Campos de usuario y contraseña (LoginScreen).
                //  2. Ambos campos deben tener información; un usuario de puros espacios se rechaza (isBlank).
                //  3. Se rechaza un usuario de más de 30 caracteres o una contraseña de más de 64.
                //  4. La contraseña se muestra como •••• (PasswordVisualTransformation).
                //  5. Mensajes genéricos: nunca dicen qué campo falló ni muestran excepciones.
                //  6. En Logcat (tag AccesoSeguro) solo se registran eventos, nunca usuario ni contraseña.
                //  7. Con datos válidos el servidor (login.php) confirma y aparece "Acceso concedido".
                //  Crear cuenta: registro.php guarda solo el hash de la contraseña (password_hash).
                //  Extra: contraseña de mínimo 8 caracteres, lista blanca de caracteres para el usuario,
                //  espera de 30 s tras 3 fallos seguidos y la contraseña nunca se guarda en el teléfono.
                val sesion by sesionViewModel.sesion.collectAsState()
                val bienvenida by sesionViewModel.bienvenida.collectAsState()

                val actual = sesion
                if (actual == null) {
                    LoginScreen(sesionViewModel)
                } else {
                    CafeteriaApp(
                        cartViewModel, productsViewModel, ordersViewModel, usuariosViewModel, consultaWebViewModel,
                        baristaViewModel,
                        chatViewModel,
                        soporteViewModel,
                        sesion = actual,
                        onCerrarSesion = sesionViewModel::cerrarSesion
                    )
                    bienvenida?.let { BienvenidaDialog(it, onCerrar = sesionViewModel::bienvenidaMostrada) }
                }
            }
        }
    }

    // Práctica 7: URL preasignada de rol (cafeteria://rol?codigo=XXXX). Si la app ya estaba
    // abierta llega por onNewIntent; el código se lleva a la pestaña Soporte para aceptarlo.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recibirUrlPreasignada(intent)
    }

    private fun recibirUrlPreasignada(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "cafeteria" && uri.host == "rol") {
            uri.getQueryParameter("codigo")?.let(soporteViewModel::recibirInvitacion)
        }
    }

    // Práctica 7: presencia. Con la app al frente el dispositivo aparece "conectado";
    // al irse al fondo se marca desconectado (el admin lo ve y puede reasignar).
    override fun onStart() {
        super.onStart()
        soporteViewModel.primerPlano(true)
    }

    override fun onStop() {
        soporteViewModel.primerPlano(false)
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CafeteriaApp(
    cartViewModel: CartViewModel,
    productsViewModel: ProductsViewModel,
    ordersViewModel: OrdersViewModel,
    usuariosViewModel: UsuariosViewModel,
    consultaWebViewModel: ConsultaWebViewModel,
    baristaViewModel: BaristaViewModel,
    chatViewModel: ChatViewModel,
    soporteViewModel: SoporteViewModel,
    sesion: Sesion,
    onCerrarSesion: () -> Unit
) {
    val cartItems by cartViewModel.cartItems.collectAsState()
    val checkout by ordersViewModel.checkout.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var mostrandoCheckout by remember { mutableStateOf(false) }
    var confirmarSalida by remember { mutableStateOf(false) }

    // Si llega una invitación de rol por URL, se abre la pestaña Soporte
    val soporte by soporteViewModel.estado.collectAsState()
    LaunchedEffect(soporte.invitacionPendiente) {
        if (soporte.invitacionPendiente != null) selectedTab = 6
    }

    if (confirmarSalida) {
        AlertDialog(
            onDismissRequest = { confirmarSalida = false },
            title = { Text("¿Cerrar sesión?") },
            text = { Text("Sesión iniciada como ${sesion.nombre} (${sesion.usuario}).") },
            confirmButton = {
                TextButton(onClick = {
                    confirmarSalida = false
                    onCerrarSesion()
                }) { Text("Cerrar sesión") }
            },
            dismissButton = { TextButton(onClick = { confirmarSalida = false }) { Text("Cancelar") } }
        )
    }

    // Cuando el servidor confirma el pedido se vacía el carrito
    LaunchedEffect(checkout.pedidoConfirmado) {
        if (checkout.pedidoConfirmado != null) {
            cartViewModel.clearCart()
            mostrandoCheckout = false
        }
    }
    checkout.pedidoConfirmado?.let { pedido ->
        PedidoConfirmadoDialog(
            pedido = pedido,
            onCerrar = ordersViewModel::cerrarConfirmacion,
            onVerPedidos = {
                ordersViewModel.cerrarConfirmacion()
                selectedTab = 2
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cafetería Móvil") },
                actions = {
                    TextButton(onClick = { confirmarSalida = true }) {
                        Text("Salir ⎋", color = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Text("🏠", fontSize = 24.sp) },
                    label = { Text("Menú") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { 
                        BadgedBox(
                            badge = {
                                if (cartItems.isNotEmpty()) {
                                    Badge {
                                        val totalItems = cartItems.sumOf { it.quantity }
                                        Text(totalItems.toString())
                                    }
                                }
                            }
                        ) {
                            Text("🛒", fontSize = 24.sp)
                        }
                    },
                    label = { Text("Carrito") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Text("🧾", fontSize = 24.sp) },
                    label = { Text("Pedidos") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
                NavigationBarItem(
                    icon = { Text("👤", fontSize = 24.sp) },
                    label = { Text("Usuarios") },
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 }
                )
                NavigationBarItem(
                    icon = { Text("🌐", fontSize = 24.sp) },
                    label = { Text("Consulta") },
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 }
                )
                // Prácticas 7, 9 y 10: reportes compartidos en Firestore con roles y seguridad
                NavigationBarItem(
                    icon = { Text("🛠️", fontSize = 24.sp) },
                    label = { Text("Soporte") },
                    selected = selectedTab == 6,
                    onClick = { selectedTab = 6 }
                )
                // Solo la cuenta del barista ve la pantalla para marcar pedidos listos
                if (sesion.esBarista) {
                    NavigationBarItem(
                        icon = { Text("☕", fontSize = 24.sp) },
                        label = { Text("Barista") },
                        selected = selectedTab == 5,
                        onClick = { selectedTab = 5 }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (selectedTab) {
                0 -> MenuScreen(productsViewModel, cartViewModel)
                1 -> if (mostrandoCheckout && cartItems.isNotEmpty()) {
                    CheckoutScreen(cartItems, ordersViewModel, onVolver = { mostrandoCheckout = false })
                } else {
                    CartScreen(cartViewModel, onConfirmar = { mostrandoCheckout = true })
                }
                // Práctica 6: cada pedido abre sus avisos con el barista. Desde aquí este teléfono
                // es el Dispositivo A (cliente, se identifica con el teléfono del pedido); si la
                // cuenta es de barista también puede escribir como Dispositivo B.
                2 -> PedidosScreen(ordersViewModel, onAvisos = { pedido ->
                    chatViewModel.abrir(pedido.folio, pedido.phone, sesion.token.takeIf { sesion.esBarista })
                })
                3 -> UsuariosScreen(usuariosViewModel)
                5 -> if (sesion.esBarista) BaristaScreen(baristaViewModel, sesion, chatViewModel) else ConsultaWebScreen(consultaWebViewModel)
                6 -> SoporteScreen(soporteViewModel)
                else -> ConsultaWebScreen(consultaWebViewModel)
            }
        }
    }
    // Práctica 6: la conversación de avisos se abre encima de cualquier pestaña
    ChatDialog(chatViewModel)
}

@Composable
fun MenuScreen(productsViewModel: ProductsViewModel, cartViewModel: CartViewModel) {
    val query by productsViewModel.query.collectAsState()
    val uiState by productsViewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        SearchField(
            query = query,
            onQueryChange = productsViewModel::onQueryChange
        )

        when (val state = uiState) {
            is ProductsUiState.Loading -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(6) {
                        SkeletonProductCard()
                    }
                }
            }

            is ProductsUiState.Success -> {
                Text(
                    text = "${state.products.size} producto(s)",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.products, key = { it.id }) { product ->
                        ProductCard(product, cartViewModel)
                    }
                }
            }

            is ProductsUiState.Empty -> MessageState(
                emoji = "🔍",
                title = "Sin resultados",
                message = if (state.query.isBlank()) "No hay productos disponibles por ahora."
                          else "No encontramos productos para \"${state.query}\"."
            )

            is ProductsUiState.Error -> MessageState(
                emoji = "📡",
                title = "No se pudieron cargar los productos",
                message = state.message,
                actionLabel = "Reintentar",
                onAction = productsViewModel::retry
            )
        }
    }
}

@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Buscar café, postres...") },
        leadingIcon = { Text("🔍", fontSize = 18.sp) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Text("✕", fontSize = 18.sp)
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun MessageState(
    emoji: String,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(emoji, fontSize = 56.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(message, color = Color.Gray, fontSize = 14.sp)
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun ProductCard(product: Product, cartViewModel: CartViewModel) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            // Requiere Coil para cargar imagenes desde URL
            // Como no podemos agregar librerías fácil sin gradle sync,
            // dejaremos un Box de color si no hay imagen (o usaremos un placeholder)
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color.LightGray)) {
                // Aquí iría la imagen real con AsyncImage(model = product.imageUrl)
                // Para el prototipo nativo, usamos texto como placeholder
                Text("☕", fontSize = 48.sp, modifier = Modifier.align(Alignment.Center))
            }
            
            Column(modifier = Modifier.padding(8.dp)) {
                Text(text = product.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(text = product.description, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.height(32.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = formatPrice(product.price), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { 
                            cartViewModel.addToCart(product)
                            Toast.makeText(context, "${product.name} agregado", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Agregar", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SkeletonProductCard() {
    val shimmerColors = listOf(
        Color.LightGray.copy(alpha = 0.6f),
        Color.LightGray.copy(alpha = 0.2f),
        Color.LightGray.copy(alpha = 0.6f)
    )

    val transition = rememberInfiniteTransition(label = "")
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000),
            repeatMode = RepeatMode.Restart
        ), label = ""
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnim.value, y = translateAnim.value)
    )

    Card(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth()
            .height(220.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Spacer(modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(brush))
            Column(modifier = Modifier.padding(8.dp)) {
                Spacer(modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(16.dp)
                    .background(brush))
                Spacer(modifier = Modifier.height(8.dp))
                Spacer(modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(12.dp)
                    .background(brush))
            }
        }
    }
}

@Composable
fun CartScreen(cartViewModel: CartViewModel, onConfirmar: () -> Unit) {
    val cartItems by cartViewModel.cartItems.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (cartItems.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Tu carrito está vacío", color = Color.Gray)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tus Productos", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Button(
                    onClick = { cartViewModel.clearCart() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.1f)),
                    contentPadding = PaddingValues(8.dp),
                    elevation = null
                ) {
                    Text("🗑️ Vaciar todo", color = Color.Red)
                }
            }
            
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(cartItems, key = { it.product.id }) { item ->
                    SwipeableCartItem(
                        onDismiss = { cartViewModel.removeFromCart(item.product.id) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.LightGray),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("☕")
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.product.name, fontWeight = FontWeight.Bold)
                                Text("${formatPrice(item.product.price)} x ${item.quantity}", color = Color.Gray)
                            }
                            Text(formatPrice(item.product.price * item.quantity), fontWeight = FontWeight.Bold)
                        }
                    }
                    HorizontalDivider()
                }
            }
            
            val total = cartItems.sumOf { it.product.price * it.quantity }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total:", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(formatPrice(total), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onConfirmar, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Confirmar Pedido", fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun SwipeableCartItem(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(targetValue = offsetX, label = "swipeAnim")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offsetX < -200f) { // Threshold for dismissing
                            onDismiss()
                        } else {
                            offsetX = 0f
                        }
                    }
                ) { _, dragAmount ->
                    if (dragAmount < 0 || offsetX < 0) { // Only swipe left
                        offsetX += dragAmount
                    }
                }
            }
    ) {
        // Red background with trash icon
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Red)
                .padding(end = 16.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Text("🗑️", fontSize = 24.sp, color = Color.White)
        }

        // The foreground content (slides to the left)
        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffsetX.toInt(), 0) }
                .background(MaterialTheme.colorScheme.surface)
        ) {
            content()
        }
    }
}