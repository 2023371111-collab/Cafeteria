# Evaluación de la calidad técnica: pruebas unitarias, de integración y E2E

**Proyecto:** Cafetería Móvil (app Android en Kotlin/Compose + API PHP + MySQL)
**Fecha:** 26/10/2026
**Funcionalidad crítica evaluada:** registro de un pedido (comanda)

---

## Funcionalidad crítica seleccionada

**Registrar un pedido y comprobar que se guarda.** Es el flujo de compra de la app, y en él participan varios componentes:

```
CheckoutScreen (Compose) → OrdersViewModel → ValidadorPedido
        → OrderRepository (cliente HTTP) → backend/api/pedidos.php → MySQL (pedidos + pedido_detalle)
        → consulta por folio (GET pedidos.php?folio=...) → PedidosScreen
```

Esta funcionalidad tiene suficiente lógica para evaluarla:
- El cliente valida el nombre, el teléfono y las notas.
- Solo se envían ids y cantidades. **El servidor pone los precios** y calcula el total en centavos.
- Hay límites de negocio: máximo 20 renglones, 20 piezas por producto y solo productos disponibles.
- El pedido se guarda en una **transacción** (encabezado + renglones, todo o nada).
- El servidor genera un **folio único** (`CMD-XXXXXX`) y reintenta si sale repetido.
- Los errores de red y de servidor se traducen a mensajes para el usuario.

---

## Fase 1. Diagnóstico de la estrategia actual

### Pruebas que había en el repositorio antes de esta evaluación

| # | Archivo | Pruebas | Qué valida | Nivel | ¿Toca la funcionalidad crítica? | ¿Se ejecuta automáticamente? |
|---|---|---|---|---|---|---|
| 1 | `app/src/test/.../ExampleUnitTest.kt` | 1 | `2 + 2 == 4` (plantilla) | Unitaria | No (sin valor) | No. Hay que correr `gradlew test` a mano |
| 2 | `app/src/test/.../ValidadorAccesoTest.kt` | 11 | Reglas de login y registro | Unitaria | No (es la funcionalidad de acceso) | No. Hay que correrla a mano |
| 3 | `app/src/test/.../ValidadorPedidoTest.kt` | 7 | Reglas de nombre, teléfono, notas, ubicación, formato de fecha | Unitaria | **Parcial**: solo la validación del formulario | No. Hay que correrla a mano |
| 4 | `app/src/androidTest/.../ExampleInstrumentedTest.kt` | 1 | Nombre del paquete (plantilla) | Instrumentada (se cuenta como integración, aunque es trivial) | No (sin valor) | No. Requiere emulador |
| — | Backend PHP (`backend/`) | 0 | — | — | — | — |

### Resumen inicial

| Nivel | Pruebas | % | Cubren el flujo de pedido |
|---|---|---|---|
| Unitarias | 19 | 95 % | 7 (solo la validación) |
| Integración | 1 (trivial) | 5 % | 0 |
| E2E | 0 | 0 % | 0 |
| **Total** | **20** | 100 % | |

**Ejecución automática:** ninguna. El proyecto no tiene repositorio git ni CI, así que las pruebas solo corren cuando alguien ejecuta Gradle a mano.

### Hallazgos del diagnóstico

1. **No había ninguna prueba del envío de pedidos.** Nada probaba `OrderRepository`, `pedidos.php`, el cálculo del total en el servidor ni la persistencia.
2. **No se podía probar `OrderRepository` de forma aislada.** La URL estaba fija en `ApiConfig.BASE_URL` (la de producción).
3. **El entorno no se podía reproducir.** La tabla `productos` no tiene script en `backend/sql/`: se creó a mano en phpMyAdmin.
4. **La configuración de pruebas estaba rota.** Gradle tomaba un JRE sin `jlink` (falla de compilación) y las librerías `androidx.test` tenían versiones desalineadas (`junit 1.1.5`, `espresso 3.5.1`), lo que provocaba `NoClassDefFoundError` al usar `ActivityScenario`. Así ninguna prueba E2E podía funcionar.

### Cambios mínimos para poder probar

| Cambio | Archivo | Motivo |
|---|---|---|
| `OrderRepository(baseUrl = ApiConfig.BASE_URL)` | `OrderRepository.kt` | Inyectar un servidor falso o uno de pruebas. Sin cambio para la app |
| `API_URL` como `buildConfigField` (por defecto, la URL de producción) | `app/build.gradle.kts`, `ProductRepository.kt` | Compilar la E2E con `-PapiUrl=http://10.0.2.2:8080/api/` |
| HTTP permitido **solo en debug** hacia `10.0.2.2`/`localhost` | `app/src/debug/AndroidManifest.xml`, `network_security_config.xml` | El servidor de pruebas es local. Release sigue aceptando solo HTTPS |
| `db.php` lee las variables de entorno `DB_*` (con los valores actuales como respaldo) | `backend/db.php` | Apuntar la API a la base de pruebas |
| Entorno Docker (PHP 8.3 + MySQL 8, base en memoria) | `backend/pruebas/` | Integración y E2E con datos ficticios |
| Dependencias de prueba: `org.json`, `mockwebserver`, `coroutines-test`, `androidx.test` alineado | `build.gradle.kts`, `libs.versions.toml` | |

---

## Fase 2. Pruebas unitarias

**Unidad bajo prueba:** `OrderRepository` (arma la petición del pedido e interpreta la respuesta).
**Doble de prueba:** `MockWebServer` (OkHttp) sustituye a la API. No se usa internet ni base de datos. Con el doble se revisa **qué envía** el repositorio y **cómo reacciona** a cada tipo de respuesta.
**Archivo:** [`app/src/test/java/com/example/cafeteria/OrderRepositoryTest.kt`](../app/src/test/java/com/example/cafeteria/OrderRepositoryTest.kt)

| Tipo | Prueba | Entrada | Resultado esperado | Resultado obtenido |
|---|---|---|---|---|
| **Válido** | `valido_envia_solo_ids_y_cantidades_y_lee_el_pedido_guardado` | Carrito: 2 Americano + 1 Pan. El servidor responde 201 | POST a `/api/pedidos.php`; el cuerpo lleva `producto_id`/`cantidad` **sin precio ni total**; sin `latitud` ni `fcm_token`; se lee folio `CMD-7K3F9Q`, total 97.90 (llega como texto `"97.90"`) y 2 renglones | ✅ Igual |
| **Límite** | `limite_respuesta_con_nulos_y_sin_renglones_no_truena` | 20 piezas (el máximo). La respuesta trae `notas: null`, ninguna ubicación, ni folio ni `items` | Se envía `cantidad: 20`; notas `""`; la comanda usa `#1`; el estado es `recibido`; no hay renglones; total 0.0 | ✅ Igual |
| **Inválido** | `invalido_producto_con_id_no_numerico_se_rechaza_sin_usar_la_red` | Producto con id `"abc"` (menú viejo) | `ApiException` con el mensaje "Actualiza el menú." y **0 peticiones** al servidor | ✅ Igual |
| Error | `error_422_muestra_el_mensaje_del_servidor` | HTTP 422 con JSON `message` | Se muestra el mensaje del servidor tal cual | ✅ |
| Error | `error_500_sin_json_da_mensaje_generico` | HTTP 500 con HTML | "Error del servidor (500). Intenta más tarde." | ✅ |
| Error | `error_html_con_codigo_200_se_reporta_como_respuesta_no_valida` | HTTP 200 con una página HTML de hosting suspendido | "La respuesta del servidor no es válida." | ✅ |

**Salida de ejecución** (completa en [`capturas/pruebas/salida_unit_integracion.txt`](capturas/pruebas/salida_unit_integracion.txt)):

```
com.example.cafeteria.OrderRepositoryTest: tests=6 fallos=0 omitidas=0 tiempo=0.306s
    OK  error_422_muestra_el_mensaje_del_servidor
    OK  limite_respuesta_con_nulos_y_sin_renglones_no_truena
    OK  error_500_sin_json_da_mensaje_generico
    OK  error_html_con_codigo_200_se_reporta_como_respuesta_no_valida
    OK  valido_envia_solo_ids_y_cantidades_y_lee_el_pedido_guardado
    OK  invalido_producto_con_id_no_numerico_se_rechaza_sin_usar_la_red
```

**Fallos registrados:** ninguno en el código de la app. Sí hubo un fallo de entorno: Gradle usaba un JRE 25 sin `jlink`. Se resolvió ejecutando con el JDK de Android Studio (ver *Cómo ejecutar*).

---

## Fase 3. Pruebas de integración

**Componentes reales:** `OrderRepository` (cliente HTTP real) ↔ `pedidos.php` (PHP 8.3 + Apache real) ↔ MySQL 8.0. **No hay mocks:** cada petición sale por la red local y se guarda en la base.
**Archivo:** [`app/src/test/java/com/example/cafeteria/PedidosApiIntegracionTest.kt`](../app/src/test/java/com/example/cafeteria/PedidosApiIntegracionTest.kt)

### Configuración

- [`backend/pruebas/docker-compose.yml`](../backend/pruebas/docker-compose.yml): un contenedor `db` (MySQL 8.0 con la base en `tmpfs`, así que cada vez que se levanta empieza limpia) y uno `api` (PHP 8.3 + `pdo_mysql`, puerto 8080).
- Esquema: se reutilizan los scripts reales `sql/pedidos.sql` y `sql/usuarios_app.sql`.
- Las pruebas reciben la URL por `-PapiPruebasUrl`. **Sin ese parámetro se omiten (skipped), no fallan**, así que no estorban a quien no tenga Docker.

### Datos de prueba (ficticios): [`backend/pruebas/datos_prueba.sql`](../backend/pruebas/datos_prueba.sql)

| id | producto | precio | disponible |
|---|---|---|---|
| 1 | Americano de prueba | 35.00 | sí |
| 2 | Capuchino de prueba | 48.50 | sí |
| 3 | Pan de elote de prueba | 27.90 | sí |
| 4 | Frappé descontinuado | 55.00 | **no** |

Para la E2E se usa además la cuenta ficticia `cliente.e2e`. Los teléfonos son aleatorios (`55xxxxxxxx`) para que las pruebas no se mezclen.

### Casos y resultados

| Tipo | Prueba | Qué se comprueba | Resultado |
|---|---|---|---|
| **Flujo correcto** | `pedido_valido_se_guarda_con_precios_del_servidor_y_se_consulta_por_folio` | Se envía el carrito con un precio **falso de $0.01**. El servidor lo ignora y calcula 2×35.00 + 1×27.90 = **97.90**. El folio tiene formato válido. Al leer el pedido de MySQL por folio y por teléfono salen los mismos renglones, el precio unitario 35.00, la latitud y la dirección | ✅ |
| **Error relevante** | `producto_no_disponible_se_rechaza_y_no_se_guarda_nada` | El carrito trae un producto válido y uno descontinuado. Resultado: 422 "Algún producto ya no está disponible" y **no queda nada guardado** (tampoco el renglón válido) | ✅ |
| Error / límite | `mas_de_20_piezas_de_un_producto_se_rechaza_en_el_servidor` | 21 piezas → "Máximo 20 piezas de un mismo producto." y nada guardado | ✅ |
| Límite | `folio_inexistente_regresa_lista_vacia` | `CMD-ZZZZZZ` → lista vacía, sin error | ✅ |

**Evidencia en la base** ([`capturas/pruebas/mysql_pedidos_guardados.txt`](capturas/pruebas/mysql_pedidos_guardados.txt)). Solo aparecen los pedidos válidos; los rechazados no dejaron filas:

```
| id | folio      | cliente             | telefono   | total | nombre                 | precio_unitario | cantidad |
|  1 | CMD-UGSXAL | Cliente Integración | 5543258056 | 97.90 | Americano de prueba    |           35.00 |        2 |
|  1 | CMD-UGSXAL | Cliente Integración | 5543258056 | 97.90 | Pan de elote de prueba |           27.90 |        1 |
|  2 | CMD-T6AT73 | Cliente Pruebas     | 5512345678 | 70.00 | Americano de prueba    |           35.00 |        2 |   ← E2E
```

### Defectos y riesgos encontrados

| # | Hallazgo | Cómo reproducirlo | Severidad |
|---|---|---|---|
| D1 | **El carrito no tiene tope de piezas, pero el servidor sí (20).** El usuario puede agregar 21 o más piezas y solo se entera del error al enviar. | Menú → tocar "Agregar" 21 veces en el mismo producto → Carrito → Confirmar → Enviar → "Máximo 20 piezas de un mismo producto." Prueba automatizada: `mas_de_20_piezas_...`. Código: `CartViewModel.addToCart` no limita la cantidad | Media (UX / regla duplicada solo en el servidor) |
| D2 | **No hay script de la tabla `productos`.** El esquema no está versionado, así que no se puede recrear la base desde el repositorio. | Buscar `CREATE TABLE productos` en `backend/sql/`: no existe. Se tuvo que deducir de `productos.php` y `pedidos.php` | Media |
| D3 | **Credenciales en texto plano en `backend/db.php`** (y la llave de Firebase en `backend/privado/`). Si el proyecto se sube a un repositorio público, quedan expuestas. | Abrir `db.php` | **Alta** (seguridad) |
| D4 | Los scripts SQL se cargan como latin1 si no llevan `SET NAMES utf8mb4` (los acentos se corrompen). | Cargar `datos_prueba.sql` sin `SET NAMES` → "CafÃ©" | Baja (se corrigió en el script de pruebas) |

---

## Fase 4. Prueba E2E automatizada

**Herramienta:** Compose UI Test + AndroidJUnitRunner en el emulador **Pixel 6a (Android 14, API 34)**. Usa la app real (APK debug) contra la API y MySQL de pruebas en Docker. **No hay nada simulado.**
**Archivo:** [`app/src/androidTest/java/com/example/cafeteria/PedidoE2ETest.kt`](../app/src/androidTest/java/com/example/cafeteria/PedidoE2ETest.kt)
**Protección:** la prueba se omite si la app no se compiló contra el servidor de pruebas (`10.0.2.2`), para no crear pedidos reales en producción.

### Pasos, resultado esperado y resultado real

| # | Paso | Resultado esperado | Resultado real |
|---|---|---|---|
| 0 | Borrar la sesión, el carrito y los datos recordados (SharedPreferences) y abrir `MainActivity` | Aparece el login | ✅ |
| 1 | Escribir el usuario `cliente.e2e` y la contraseña → "Iniciar sesión" → "Continuar" | `login.php` acepta; se ve el menú | ✅ |
| 2 | El menú carga desde la API → tocar "Agregar" 2 veces en *Americano de prueba* | 2 piezas en el carrito | ✅ [captura 01](capturas/pruebas/01_menu_api_pruebas.png) |
| 3 | Carrito → "Confirmar Pedido" → Nombre "Cliente Pruebas", Teléfono 5512345678 | Formulario sin errores | ✅ [captura 02](capturas/pruebas/02_formulario_lleno.png) |
| 4 | "☁️ Enviar pedido" | Diálogo "✅ ¡Pedido enviado!" con un folio `CMD-XXXXXX` generado por el servidor y TOTAL **$70.00** | ✅ Folio `CMD-ZABT62`, $70.00 ([captura 03](capturas/pruebas/03_comanda_con_folio.png)) |
| 5 | "Ver mis pedidos" → escribir el folio → "Consultar" | Aparece "Pedido CMD-ZABT62" con "2 × Americano de prueba" (se vuelve a leer de MySQL) | ✅ [captura 04](capturas/pruebas/04_consulta_por_folio.png) |

**Registro de ejecución** ([`capturas/pruebas/salida_e2e_instrument.txt`](capturas/pruebas/salida_e2e_instrument.txt)):

```
Time: 18.245
OK (1 test)
```

### Fallos registrados durante la E2E

| Ejecución | Fallo | Causa | Acción |
|---|---|---|---|
| 1 | `NoClassDefFoundError: ActivityInvoker$-CC` | Versiones de `androidx.test` desalineadas (`ext.junit` 1.1.5 / `espresso` 3.5.1 contra `rules`/`core` 1.6.1) | Se subió a `ext.junit` 1.2.1 y `espresso` 3.6.1 |
| 2 | `ComposeTimeoutException` esperando la confirmación; no llegó ningún POST al servidor | El volcado de la pantalla (Logcat, tag `PedidoE2E`) mostró el error "Solo letras y espacios" en el campo Nombre: el dato de prueba "Cliente **E2E**" lleva un dígito. **La app se comportó correctamente.** Fue un error en el diseño de los datos de prueba | Se cambió el nombre a "Cliente Pruebas" |
| 3–4 | — | — | ✅ Pasa |

---

## Fase 5. Auditoría y conclusión técnica

### 1. Resultados consolidados

| Nivel | Pruebas nuevas | Total actual | Pasan | Fallan | Omitidas sin servidor |
|---|---|---|---|---|---|
| Unitarias | 6 | 25 | 25 | 0 | 0 |
| Integración | 4 | 5 (4 reales + 1 de plantilla) | 5 | 0 | 4 (por diseño) |
| E2E | 1 | 1 | 1 | 0 | 1 (por diseño) |
| **Total** | **11** | **31** | **31** | **0** | |

### 2. Distribución porcentual (pirámide de pruebas)

| Nivel | Antes | % | Después | % |
|---|---|---|---|---|
| E2E | 0 | 0 % | 1 | 3.2 % |
| Integración | 1* | 5 % | 5 | 16.1 % |
| Unitarias | 19 | 95 % | 25 | 80.6 % |

\* De plantilla; no valida nada del sistema.

```
Antes                              Después
                                          /\          E2E  1  (3.2 %)
      (sin E2E)                          /  \
      ·  1 integración trivial          /----\        Integración  5  (16.1 %)
 ┌──────────────────────┐              /      \
 │  19 unitarias (95 %) │             /--------\      Unitarias  25  (80.6 %)
 └──────────────────────┘            /__________\
   Forma: "bloque" sin pico          Forma: pirámide (base ancha, pico pequeño)
```

### 3. La capa más débil: **integración**

- **Antes no existía.** El contrato app ↔ API (nombres de campos JSON, DECIMAL como texto, códigos 201/422/500, transacciones) no tenía ninguna prueba. Las validaciones unitarias cubrían el formulario, pero un cambio en `pedidos.php` podía romper la app sin que ninguna prueba lo detectara.
- **Hoy sigue siendo la más delgada en proporción a su riesgo.** Solo cubre `pedidos.php`. Quedan sin pruebas `login.php`, `registro.php`, `barista.php` (cambio de estado con token de sesión y rol) y `fcm.php`, que manejan autenticación, autorización y notificaciones.
- **No corre sola.** Depende de levantar Docker a mano.
- Los defectos D1 y D2 solo salieron al montar la integración real, lo que confirma que es la capa donde se esconden los problemas de este proyecto.

### 4. Mejoras priorizadas por riesgo

| Prioridad | Mejora | Riesgo que reduce | Esfuerzo |
|---|---|---|---|
| **1 (alta)** | **Pruebas de integración de `barista.php` y de la sesión.** Casos: sin token → 401; token vencido → 401; cliente que intenta marcar "listo" → rechazado; barista marca "listo" → cambia el estado en MySQL. Además, sacar las credenciales de `db.php` a variables de entorno (D3) y versionar `productos.sql` (D2). | Escalada de privilegios y acceso no autorizado a pedidos ajenos; fuga de credenciales | Medio. El entorno Docker ya existe |
| **2 (media-alta)** | **Pipeline automático (git + GitHub Actions).** En cada push: unitarias. En cada PR: levantar `backend/pruebas` como servicio y correr integración. Antes de una entrega: E2E en un emulador (Gradle Managed Devices). Además, corregir D1 limitando el carrito a 20 piezas y cubrirlo con una prueba unitaria de `CartViewModel`. | Regresiones que hoy nadie detecta porque ninguna prueba corre automáticamente | Bajo-medio |

### 5. Qué ejecutar y cuándo

| Momento | Pruebas | Comando | Duración aprox. |
|---|---|---|---|
| **En cada cambio de código** (local y en cada push) | Las 25 unitarias + las 4 de integración si Docker está arriba | `gradlew :app:testDebugUnitTest -PapiPruebasUrl=http://localhost:8080/api/` | < 1 min (las pruebas en sí tardan < 1 s) |
| **En cada cambio de `backend/` o de los repositorios** | Las de integración (obligatorio) | igual que arriba, con `docker compose ... up` | ~1 min |
| **Antes de una entrega** | Todas + E2E en el emulador + revisión manual de lo que no está automatizado (reloj Wear OS, notificación FCM, mapa) | `gradlew :app:connectedDebugAndroidTest -PapiUrl=http://10.0.2.2:8080/api/` | ~3 min |

---

## Cómo ejecutar

```powershell
# 0) JDK con jlink (el de Android Studio); Gradle 9 pide toolchain 25
$env:JAVA_HOME = "D:\Android Studio\jbr"
$g = "-Porg.gradle.java.installations.auto-detect=false", "-Porg.gradle.java.installations.paths=D:/Android Studio/jbr"

# 1) Entorno de pruebas (PHP + MySQL con datos ficticios)
docker compose -f backend/pruebas/docker-compose.yml up -d --build

# 2) Unitarias + integración
.\gradlew.bat :app:testDebugUnitTest @g -PapiPruebasUrl=http://localhost:8080/api/

# 3) E2E (con el emulador encendido)
.\gradlew.bat :app:connectedDebugAndroidTest @g -PapiUrl=http://10.0.2.2:8080/api/

# 4) Apagar el entorno (la base se borra sola)
docker compose -f backend/pruebas/docker-compose.yml down
```

Reportes HTML: `app/build/reports/tests/testDebugUnitTest/index.html` y `app/build/reports/androidTests/connected/debug/index.html`.
