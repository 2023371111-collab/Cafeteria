package com.example.cafeteria

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class Product(
    val id: String,
    val name: String,
    val description: String,
    val price: Double,
    val imageUrl: String
)

data class CartItem(
    val product: Product,
    var quantity: Int
)

// Práctica 12 (DEF-11): el ViewModel ya no guarda el Context que recibe (si era una Activity
// la retenía en memoria al rotar la pantalla: Lint StaticFieldLeak). Solo se usa el
// applicationContext, que vive lo mismo que la app.
class CartViewModel(context: Context) : ViewModel() {
    private val _cartItems = MutableStateFlow<List<CartItem>>(emptyList())
    val cartItems: StateFlow<List<CartItem>> = _cartItems.asStateFlow()

    private val sharedPrefs = context.applicationContext.getSharedPreferences("cart_prefs", Context.MODE_PRIVATE)

    init {
        loadCart()
    }

    fun addToCart(product: Product) {
        _cartItems.update { currentCart ->
            val existingItem = currentCart.find { it.product.id == product.id }
            if (existingItem != null) {
                currentCart.map {
                    if (it.product.id == product.id) it.copy(quantity = it.quantity + 1) else it
                }
            } else {
                currentCart + CartItem(product, 1)
            }
        }
        saveCart()
    }

    fun removeFromCart(productId: String) {
        _cartItems.update { currentCart ->
            currentCart.filterNot { it.product.id == productId }
        }
        saveCart()
    }

    fun clearCart() {
        _cartItems.value = emptyList()
        saveCart()
    }

    private fun saveCart() {
        val items = _cartItems.value
        val jsonArray = JSONArray()
        items.forEach { item ->
            val jsonObject = JSONObject().apply {
                put("id", item.product.id)
                put("name", item.product.name)
                put("description", item.product.description)
                put("price", item.product.price)
                put("imageUrl", item.product.imageUrl)
                put("quantity", item.quantity)
            }
            jsonArray.put(jsonObject)
        }
        sharedPrefs.edit().putString("cart_data", jsonArray.toString()).apply()
    }

    private fun loadCart() {
        val cartData = sharedPrefs.getString("cart_data", null) ?: return
        // Un carrito guardado corrupto ya no cierra la app al iniciar: se descarta
        try {
            val jsonArray = JSONArray(cartData)
            val loadedItems = mutableListOf<CartItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val product = Product(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    description = obj.getString("description"),
                    price = obj.getDouble("price"),
                    imageUrl = obj.getString("imageUrl")
                )
                loadedItems.add(CartItem(product, obj.getInt("quantity")))
            }
            _cartItems.value = loadedItems
        } catch (e: org.json.JSONException) {
            sharedPrefs.edit().remove("cart_data").apply()
        }
    }
}

class CartViewModelFactory(context: Context) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return CartViewModel(appContext) as T
    }
}
