# Unidad 2: Buscador Reactivo y Conexión API

**Proyecto:** Cafetería Móvil (Android · Kotlin · Jetpack Compose)
**Backend:** PHP + MySQL

---

## 1. Conexión de la app con el backend y la base de datos

### Arquitectura

```
App Android (Compose)
   │  MenuScreen  ──observa──►  ProductsViewModel  ──llama──►  ProductRepository
   │                                                              │ HTTP GET (JSON)
   ▼                                                              ▼
                                         backend/api/productos.php ──PDO──► MySQL (tabla productos)
```

| Capa | Archivo | Responsabilidad |
|---|---|---|
| Base de datos | MySQL, tabla `productos` | Guarda `id, nombre, descripcion, precio, imagen_url, disponible` |
| Conexión BD | `backend/db.php` | Crea la conexión PDO (UTF-8, excepciones activas, consultas preparadas reales) |
| API | `backend/api/productos.php` | Devuelve los productos disponibles en JSON; filtra con `?q=` |
| Cliente HTTP | `ProductRepository.kt` | Hace la petición GET, convierte el JSON a objetos `Product` y traduce los errores |
| Estado | `ProductsViewModel.kt` | Maneja la búsqueda y expone el estado de la pantalla |
| Interfaz | `MainActivity.kt` (`MenuScreen`) | Dibuja el buscador, la carga, los resultados y los errores |

### Endpoint

| Petición | Resultado |
|---|---|
| `GET /cafeteria-api/api/productos.php` | Todos los productos con `disponible = 1` |
| `GET /cafeteria-api/api/productos.php?q=cafe` | Productos cuyo nombre o descripción contiene "cafe" |

Ejemplo de respuesta:

```json
{
  "error": false,
  "total": 8,
  "productos": [
    { "id": 1, "nombre": "Cafe Americano", "descripcion": "Cafe negro suave, recien preparado.",
      "precio": "25.00", "imagen_url": null },
    ...
  ]
}
```

La búsqueda usa una **consulta preparada** (`LIKE :q`), así que el texto que escribe el usuario nunca se mezcla con el SQL. Esto evita la inyección SQL.

### En la app

- Se agregó el permiso `android.permission.INTERNET` en `AndroidManifest.xml`.
- La URL del servidor está en `ApiConfig.BASE_URL` (`ProductRepository.kt`).
- La petición corre en `Dispatchers.IO`, fuera del hilo principal, para no congelar la interfaz.
- Los productos ya **no están escritos en el código**: vienen de la base de datos.

> 📸 **Captura 1:** `test_conexion.php` abierto en el navegador ("Conexión exitosa" y la tabla de productos).
>
> 📸 **Captura 2:** `productos.php` abierto en el navegador (el JSON completo).
>
> 📸 **Captura 3:** `productos.php?q=cafe` abierto en el navegador (JSON filtrado).
>
> 📸 **Captura 4:** La tabla `productos` en phpMyAdmin.

---

## 2. Buscador asíncrono optimizado en tiempo real

El usuario escribe en el campo de búsqueda y los resultados se actualizan solos, sin presionar ningún botón. Para no saturar el servidor con una petición por cada letra, el `ProductsViewModel` aplica tres optimizaciones con **Kotlin Flow**:

```kotlin
_query
    .debounce(300)            // 1. espera 300 ms después de la última tecla
    .map { it.trim() }
    .distinctUntilChanged()   // 2. no repite la petición si el texto no cambió
combine(busqueda, retryTrigger) { q, _ -> q }
    .collectLatest { q -> cargar(q) }  // 3. cancela la búsqueda anterior si llega una nueva
```

| Técnica | Qué resuelve |
|---|---|
| `debounce(300)` | Escribir "capuccino" hace **1** petición en lugar de 9 |
| `distinctUntilChanged()` | Agregar un espacio al final no vuelve a consultar |
| `collectLatest` | Si llega una respuesta lenta de una búsqueda vieja, se descarta y no reemplaza los resultados nuevos |

El campo tiene un botón **✕** para borrar la búsqueda y volver a la lista completa.

> 📸 **Captura 5:** Menú completo cargado desde la API (con el contador "N producto(s)").
>
> 📸 **Captura 6:** Buscador con texto escrito (por ejemplo "cafe") y los resultados filtrados.

---

## 3. Manejo de errores de red y estados de carga

La pantalla siempre está en uno de cuatro estados, definidos con una `sealed interface`:

```kotlin
sealed interface ProductsUiState {
    data object Loading : ProductsUiState                        // skeleton animado
    data class Success(val products: List<Product>) : ProductsUiState
    data class Empty(val query: String) : ProductsUiState         // "Sin resultados"
    data class Error(val message: String) : ProductsUiState       // mensaje + "Reintentar"
}
```

| Estado | Qué ve el usuario |
|---|---|
| **Loading** | Tarjetas *skeleton* con animación *shimmer* mientras llega la respuesta real |
| **Success** | La cuadrícula de productos |
| **Empty** | 🔍 "No encontramos productos para "xyz"." |
| **Error** | 📡 Mensaje claro y botón **Reintentar** |

`ProductRepository` traduce cada excepción técnica a un mensaje entendible:

| Situación | Excepción | Mensaje mostrado |
|---|---|---|
| Sin internet / modo avión | `UnknownHostException` | "Sin conexión a internet. Revisa tu red." |
| Servidor apagado | `ConnectException` | "No se pudo conectar con el servidor." |
| Servidor lento (más de 8 s) | `SocketTimeoutException` | "El servidor tardó demasiado en responder." |
| Error HTTP 500 (por ejemplo, falla la BD) | Código ≠ 2xx | El `message` que manda la API, por ejemplo "Error de conexión con la base de datos." |
| JSON mal formado | `JSONException` | "La respuesta del servidor no es válida." |

El botón **Reintentar** vuelve a lanzar la misma búsqueda sin que el usuario tenga que escribirla otra vez.

> 📸 **Captura 7:** Estado de carga (skeleton). Tip: activa la red lenta en el emulador para que dure más.
>
> 📸 **Captura 8:** Búsqueda sin resultados (por ejemplo "pizza").
>
> 📸 **Captura 9:** Error con el teléfono en **modo avión** y el botón "Reintentar".
>
> 📸 **Captura 10:** Después de quitar el modo avión y presionar "Reintentar", los productos cargan de nuevo.

---

## Conclusión

La app ahora obtiene los productos en tiempo real desde la base de datos MySQL a través de una API PHP. El buscador es reactivo y eficiente gracias a `debounce`, `distinctUntilChanged` y `collectLatest`. La interfaz comunica en todo momento lo que está pasando: cargando, resultados, sin resultados o error con opción de reintentar.
