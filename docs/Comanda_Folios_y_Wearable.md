# Comanda, Folios Alfanuméricos y Notificación al Wearable

**Proyecto:** Cafetería Móvil (Android · Kotlin · Jetpack Compose)
**Backend:** PHP + MySQL
**Wearable:** Wear OS · Firebase Cloud Messaging

---

## 1. Comanda generada desde el carrito

Una **comanda** es el ticket del pedido: qué se pidió, cuántas piezas, para quién y cuánto se pagó. En la app, la comanda queda **enlazada al carrito**: al enviar el carrito, el servidor guarda el pedido y regresa sus datos, y con esa respuesta se arma la comanda.

### Flujo

```
Carrito (CartViewModel)
   │  "Enviar pedido"
   ▼
OrdersViewModel ──POST JSON──► backend/api/pedidos.php ──► MySQL (pedidos + pedido_detalle)
   ▲                                   │
   └──────── pedido guardado ◄─────────┘
   │
   ▼
ComandaDialog  (ticket con folio, productos, notas, entrega y total)
```

La comanda se arma con lo que **regresa el servidor**, no con lo que había en el teléfono. Los precios y el total salen de la base de datos, así que no se pueden alterar desde la app.

### Contenido de la comanda

| Sección | Dato |
|---|---|
| Encabezado | Nombre de la cafetería, **folio** y fecha/hora |
| Cliente | Nombre, teléfono y tipo de entrega (para recoger o dirección de entrega) |
| Renglones | Cantidad, producto, precio unitario e importe |
| Notas | Indicaciones del cliente (por ejemplo "sin azúcar") |
| Cierre | **Total**, número de piezas y estado del pedido |

### Dónde aparece

- **Al enviar el carrito**: la comanda se abre en pantalla completa en cuanto el servidor confirma el pedido.
- **En la pestaña Pedidos**: cada pedido tiene el botón **🧾 Ver comanda** para consultarla otra vez.
- **Compartir**: el botón **📤 Compartir comanda** la manda como texto (WhatsApp, correo, etc.).

| Archivo | Responsabilidad |
|---|---|
| `ComandaUi.kt` | Dibuja el ticket (`ComandaTicket`), la pantalla (`ComandaDialog`) y el texto para compartir |
| `PedidosUi.kt` | Muestra la comanda al confirmar el pedido y el botón "Ver comanda" en cada pedido |
| `OrdersViewModel.kt` | Envía el carrito y guarda el pedido confirmado |

> 📸 **Captura 1:** El carrito con productos, antes de confirmar.
>
> 📸 **Captura 2:** La pantalla "Confirmar pedido" con nombre, teléfono y notas llenos.
>
> 📸 **Captura 3:** La comanda que aparece justo después de enviar el pedido ("¡Pedido enviado! Esta es tu comanda").
>
> 📸 **Captura 4:** La pestaña **Pedidos** con el botón "🧾 Ver comanda" en cada pedido.
>
> 📸 **Captura 5:** El menú de Android para compartir, después de tocar "📤 Compartir comanda".

---

## 2. Folios alfanuméricos sin repetir

Antes, cada pedido se identificaba con su número consecutivo (`#1`, `#2`, `#3`…). Ahora cada comanda tiene un **folio alfanumérico** que **nunca se repite**:

```
CMD-7K3F9Q
 │    └── 6 caracteres al azar (letras y números)
 └─────── prefijo de "comanda"
```

### Cómo se genera (`pedidos.php`)

```php
const FOLIO_ALFABETO = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';  // sin 0/O ni 1/I

function generarFolio(): string {
    $folio = 'CMD-';
    for ($i = 0; $i < 6; $i++) {
        $folio .= FOLIO_ALFABETO[random_int(0, strlen(FOLIO_ALFABETO) - 1)];
    }
    return $folio;
}
```

| Decisión | Por qué |
|---|---|
| Alfabeto **sin 0, O, 1 ni I** | No se confunden al dictar el folio en el mostrador |
| 32 caracteres × 6 posiciones | 32⁶ ≈ **mil millones** de combinaciones |
| `random_int()` | Generador seguro: los folios no se pueden adivinar ni predecir |
| Lo genera el **servidor** | La app no puede inventar ni duplicar un folio |

### Cómo se garantiza que no se repita

La garantía la da la **base de datos**, no el código:

```sql
folio CHAR(10) NOT NULL,
UNIQUE INDEX uq_pedidos_folio (folio)
```

Si por azar se genera un folio que ya existe, MySQL rechaza el `INSERT` con el error **1062 (clave duplicada)** y `pedidos.php` genera otro (hasta 5 intentos). Así es imposible que dos pedidos tengan el mismo folio, aunque lleguen al mismo tiempo.

Los pedidos que ya existían recibieron un folio a partir de su id en base 36 (por ejemplo `CMD-000003`), que tampoco se repite.

### Consulta por folio

En la pestaña **Pedidos**, la caja ahora acepta **teléfono o folio**:

| Se escribe | Consulta | Resultado |
|---|---|---|
| `7721047703` | `GET pedidos.php?telefono=7721047703` | Los pedidos de ese teléfono |
| `CMD-7K3F9Q` (o `cmd7k3f9q`, o `7K3F9Q`) | `GET pedidos.php?folio=CMD-7K3F9Q` | Solo esa comanda |

La app normaliza lo que se escribe (mayúsculas y guion), y tanto la app (`ValidadorPedido.folio`) como el servidor validan el formato.

> 📸 **Captura 6:** Una comanda mostrando su folio alfanumérico (`CMD-…`).
>
> 📸 **Captura 7:** La tabla `pedidos` en phpMyAdmin con la columna `folio`.
>
> 📸 **Captura 8:** La estructura de la tabla `pedidos` en phpMyAdmin (pestaña "Estructura"), donde se ve el índice **UNIQUE** `uq_pedidos_folio`.
>
> 📸 **Captura 9:** La pestaña **Pedidos** buscando por folio, con una sola comanda como resultado.
>
> 📸 **Captura 10:** Error de validación al escribir un folio incompleto (por ejemplo `CMD-12`).

---

## 3. Notificación "pedido listo" en el reloj (Wear OS)

Cuando el pedido está listo, el barista lo marca en la app y el **reloj del cliente vibra** y muestra el aviso, sin que tenga que sacar el teléfono.

### Piezas que intervienen

```
Barista (pestaña ☕ Barista)
   │  "🔔 Pedido listo"
   ▼
barista.php ──► MySQL (estado = 'listo')
   │
   │  token FCM del cliente
   ▼
Firebase Cloud Messaging (Google)
   │
   ▼
Teléfono del cliente (CafeteriaMessagingService) ── notificación en el teléfono
   │
   │  Data Layer de Wear OS (ruta /pedido-listo)
   ▼
Reloj Wear OS (PedidoListoService) ── vibra y muestra "¡Tu pedido está listo!"
```

| Pieza | Archivo | Responsabilidad |
|---|---|---|
| Pantalla del barista | `BaristaUi.kt`, `BaristaViewModel.kt` | Lista los pedidos pendientes (se actualiza cada 15 s) y los marca **listo** o **entregado** |
| API del barista | `backend/api/barista.php` | Cambia el estado y, al pasar a "listo", pide a FCM que avise al cliente |
| Envío a Firebase | `backend/fcm.php` | Firma un JWT con la cuenta de servicio, obtiene el token de acceso de Google y llama a la API HTTP v1 de FCM |
| Teléfono | `NotificacionesPedido.kt` | Obtiene el token FCM, recibe el mensaje, muestra la notificación y la reenvía al reloj |
| Reloj | módulo `wear/` (`PedidoListoService.kt`, `RelojActivity.kt`) | Recibe el mensaje del teléfono, vibra y muestra la notificación |

### Paso a paso

1. **El cliente envía su pedido.** La app pide permiso de notificaciones y manda, junto con el carrito, el **token FCM** del teléfono (un identificador único del dispositivo). Se guarda en la columna `pedidos.fcm_token`.
2. **El barista ve el pedido** en la pestaña ☕ Barista, que indica si el cliente activó el aviso (🔔) o no (🔕).
3. **El barista toca "🔔 Pedido listo".** `barista.php` cambia el estado a `listo` y llama a Firebase con el token del cliente.
4. **Firebase entrega el mensaje** al teléfono, aunque la app esté cerrada. Es un mensaje de **datos** con prioridad alta, para que la app pueda procesarlo y reenviarlo.
5. **El teléfono lo reenvía al reloj** por la **Data Layer** de Wear OS (`MessageClient.sendMessage`, ruta `/pedido-listo`).
6. **El reloj vibra** (dos pulsos largos) y muestra: *"¡Tu pedido está listo! Pasa al mostrador."*
7. Cuando el cliente recoge su pedido, el barista toca **"✅ Marcar entregado"** y el pedido sale de la lista.

### La app del reloj

Es un módulo aparte (`wear/`) con el **mismo `applicationId`** que la app del teléfono (`com.example.cafeteria`). Wear OS solo entrega mensajes de la Data Layer entre apps con el mismo paquete y la misma firma. La notificación del teléfono usa `setLocalOnly(true)` para que Android no la copie al reloj, porque el reloj muestra la suya propia y si no llegarían dos.

### Seguridad

| Medida | Para qué |
|---|---|
| **Rol** `barista` en `usuarios_app` | Solo esa cuenta ve la pestaña Barista y puede marcar pedidos |
| **Token de sesión** que entrega `login.php` | `barista.php` lo revisa en cada petición (encabezado `X-Sesion`); en la base solo se guarda su **hash SHA-256** y vence a las 12 horas |
| Cambios de estado en orden | Solo `recibido → listo → entregado`; no se puede brincar ni regresar un estado |
| Llave de Firebase en el **servidor** | La cuenta de servicio vive en `backend/privado/`, bloqueada con `.htaccess` (responde 403); nunca va dentro de la app |
| `fcm_token` no se expone | Ninguna consulta de pedidos lo regresa |

> 📸 **Captura 11:** El diálogo de Android pidiendo permiso de notificaciones al enviar el pedido.
>
> 📸 **Captura 12:** La pestaña **☕ Barista** con un pedido "Por preparar" y la etiqueta "🔔 con aviso".
>
> 📸 **Captura 13:** El mensaje "🔔 Aviso enviado a …" después de tocar "Pedido listo".
>
> 📸 **Captura 14:** El **reloj** mostrando la notificación "¡Tu pedido está listo!". ⭐ *La más importante.*
>
> 📸 **Captura 15:** La notificación en el **teléfono**.
>
> 📸 **Captura 16:** La app del reloj abierta, con el último aviso, el folio y la hora.
>
> 📸 **Captura 17:** El pedido en estado "Listo" en la pestaña Barista, con el botón "✅ Marcar entregado".
>
> 📸 **Captura 18:** El proyecto de Firebase (consola → Configuración del proyecto) con la app `com.example.cafeteria` registrada.
>
> 📸 **Captura 19:** Los dos emuladores lado a lado (Pixel 6a y Wear OS) emparejados.
>
> 📸 **Captura 20:** La llave privada bloqueada: `…/cafeteria-api/privado/firebase-servicio.json` abierto en el navegador, mostrando **403 Forbidden**.

---

## Cambios en la base de datos

| Script | Qué hace |
|---|---|
| `backend/sql/pedidos_folio.sql` | Agrega la columna `folio` con índice `UNIQUE` y llena los pedidos anteriores |
| `backend/sql/notificaciones.sql` | Agrega `pedidos.fcm_token`, y `rol`, `token_hash` y `token_expira` a `usuarios_app` |

---

## Conclusión

El carrito ahora genera una **comanda** completa que el cliente puede consultar y compartir. Cada comanda se identifica con un **folio alfanumérico** que la propia base de datos garantiza que nunca se repite, y se puede buscar por ese folio. Por último, cuando el barista marca un pedido como listo, **Firebase Cloud Messaging** lo entrega al teléfono del cliente y este lo reenvía a su **reloj Wear OS**, que vibra y muestra el aviso. Las acciones del barista están protegidas por rol y token de sesión, y la llave de Firebase nunca sale del servidor.
