<?php
// =============================================
// API: Pedidos
// POST /api/pedidos.php                       → registra un pedido (cuerpo JSON)
// GET  /api/pedidos.php?telefono=4421234567   → pedidos de ese teléfono
// GET  /api/pedidos.php?folio=CMD-7K3F9Q      → la comanda con ese folio
// =============================================

header("Content-Type: application/json; charset=UTF-8");
header("Access-Control-Allow-Origin: *");
header("Access-Control-Allow-Methods: GET, POST, OPTIONS");
header("Access-Control-Allow-Headers: Content-Type, Idempotency-Key");

require_once __DIR__ . '/../comun.php';

const MAX_RENGLONES = 20;   // productos distintos por pedido
const MAX_CANTIDAD  = 20;   // piezas de un mismo producto

// Folio: "CMD-" + 6 caracteres al azar. Sin 0/O ni 1/I para que no se confundan
// al dictarlo: 32^6 ≈ mil millones de combinaciones.
const FOLIO_PREFIJO   = 'CMD-';
const FOLIO_ALFABETO  = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
const FOLIO_LONGITUD  = 6;
const FOLIO_INTENTOS  = 5;  // si sale repetido se genera otro
const FOLIO_REGEX     = '/^CMD-[0-9A-Z]{6}$/';
// Práctica 12: clave de idempotencia (UUID que genera la app una vez por pedido y repite en
// cada reintento). Si ya existe un pedido con esa clave se devuelve ese mismo pedido (200)
// en vez de crear otro: un reintento después de perder la respuesta ya no duplica.
const IDEM_REGEX      = '/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/';

$metodo = $_SERVER['REQUEST_METHOD'];
if ($metodo === 'OPTIONS') {
    http_response_code(204);
    exit;
}

try {
    $pdo = getConnection();
    if ($metodo === 'POST') {
        crearPedido($pdo);
    } elseif ($metodo === 'GET') {
        consultarPedidos($pdo);
    } else {
        error_api(405, 'Método no permitido.');
    }
} catch (PDOException $e) {
    if (isset($pdo) && $pdo->inTransaction()) {
        $pdo->rollBack();
    }
    error_api(500, 'Error al procesar el pedido. Intenta más tarde.');
}

// ---------------------------------------------
// POST: registrar un pedido
// Cuerpo: {"cliente":"Ana","telefono":"4421234567","notas":"sin azúcar",
//          "latitud":20.588793,"longitud":-100.389888,   ← opcionales
//          "direccion":"Av. Pie de la Cuesta 2501, Querétaro", ← opcional
//          "fcm_token":"dXk3...",                         ← opcional (aviso de "pedido listo")
//          "items":[{"producto_id":1,"cantidad":2}]}
// ---------------------------------------------
function crearPedido(PDO $pdo): void {
    $entrada = json_decode(file_get_contents('php://input'), true);
    if (!is_array($entrada)) {
        error_api(400, 'El cuerpo de la petición debe ser JSON válido.');
    }

    // Idempotencia (opcional: sin el encabezado se comporta igual que antes)
    $claveIdem = trim((string)($_SERVER['HTTP_IDEMPOTENCY_KEY'] ?? ''));
    if ($claveIdem !== '') {
        if (!preg_match(IDEM_REGEX, $claveIdem)) {
            error_api(422, 'La clave de idempotencia no es válida.');
        }
        $claveIdem = strtolower($claveIdem);
        responderSiYaExiste($pdo, $claveIdem);   // reintento de un pedido ya guardado → 200
    }

    $cliente  = trim((string)($entrada['cliente'] ?? ''));
    $telefono = trim((string)($entrada['telefono'] ?? ''));
    $notas    = trim((string)($entrada['notas'] ?? ''));
    $items    = $entrada['items'] ?? null;

    // Las mismas reglas que valida la app: nunca se confía solo en el cliente
    if (!preg_match("/^[\p{L} .'-]{3,60}$/u", $cliente)) {
        error_api(422, 'El nombre debe tener de 3 a 60 letras.');
    }
    if (!preg_match('/^\d{10}$/', $telefono)) {
        error_api(422, 'El teléfono debe tener 10 dígitos.');
    }
    if (mb_strlen($notas) > 150) {
        error_api(422, 'Las notas no pueden pasar de 150 caracteres.');
    }

    // Ubicación de entrega (opcional): llegan las dos coordenadas o ninguna
    $latitud  = $entrada['latitud']  ?? null;
    $longitud = $entrada['longitud'] ?? null;
    if ($latitud !== null || $longitud !== null) {
        $latitud  = filter_var($latitud, FILTER_VALIDATE_FLOAT);
        $longitud = filter_var($longitud, FILTER_VALIDATE_FLOAT);
        if ($latitud === false || $longitud === false
            || $latitud < -90 || $latitud > 90 || $longitud < -180 || $longitud > 180) {
            error_api(422, 'La ubicación de entrega no es válida.');
        }
        $latitud  = round($latitud, 6);   // ~10 cm, lo que cabe en DECIMAL(9,6)
        $longitud = round($longitud, 6);
    }

    // Dirección legible de esas coordenadas (opcional, solo si hay coordenadas)
    $direccion = $latitud === null ? '' : trim((string)($entrada['direccion'] ?? ''));
    if (mb_strlen($direccion) > 150) {
        error_api(422, 'La dirección de entrega no puede pasar de 150 caracteres.');
    }

    // Token FCM (opcional): para avisarle al teléfono y a su reloj cuando el pedido esté listo
    $fcmToken = trim((string)($entrada['fcm_token'] ?? ''));
    if ($fcmToken !== '' && !preg_match('/^[A-Za-z0-9:_\-]{20,255}$/', $fcmToken)) {
        error_api(422, 'El identificador de notificaciones no es válido.');
    }

    if (!is_array($items) || count($items) === 0) {
        error_api(422, 'El pedido no tiene productos.');
    }
    if (count($items) > MAX_RENGLONES) {
        error_api(422, 'Demasiados productos en un solo pedido.');
    }

    // Junta las cantidades por producto y valida cada renglón
    $cantidades = [];
    foreach ($items as $item) {
        if (!is_array($item)) {
            error_api(422, 'Producto o cantidad no válidos.');
        }
        $id   = filter_var($item['producto_id'] ?? null, FILTER_VALIDATE_INT, ['options' => ['min_range' => 1]]);
        $cant = filter_var($item['cantidad'] ?? null, FILTER_VALIDATE_INT, ['options' => ['min_range' => 1]]);
        if ($id === false || $cant === false) {
            error_api(422, 'Producto o cantidad no válidos.');
        }
        $cantidades[$id] = ($cantidades[$id] ?? 0) + $cant;
        if ($cantidades[$id] > MAX_CANTIDAD) {
            error_api(422, 'Máximo ' . MAX_CANTIDAD . ' piezas de un mismo producto.');
        }
    }

    // Los precios se toman de la base de datos, NUNCA del teléfono
    $ids    = array_keys($cantidades);
    $marcas = implode(',', array_fill(0, count($ids), '?'));
    $stmt   = $pdo->prepare("SELECT id, nombre, precio FROM productos WHERE disponible = 1 AND id IN ($marcas)");
    $stmt->execute($ids);
    $productos = [];
    foreach ($stmt->fetchAll() as $p) {
        $productos[(int)$p['id']] = $p;
    }
    if (count($productos) !== count($ids)) {
        error_api(422, 'Algún producto ya no está disponible. Actualiza el menú.');
    }

    $totalCentavos = 0;
    foreach ($cantidades as $id => $cant) {
        $totalCentavos += (int)round($productos[$id]['precio'] * 100) * $cant;
    }

    // Encabezado + renglones en una transacción: se guarda todo o nada
    $pdo->beginTransaction();
    $stmt = $pdo->prepare(
        "INSERT INTO pedidos (folio, cliente, telefono, notas, total, latitud, longitud, direccion, fcm_token, clave_idem)
         VALUES (:folio, :cliente, :telefono, :notas, :total, :latitud, :longitud, :direccion, :fcm_token, :clave_idem)"
    );
    $datos = [
        ':cliente'  => $cliente,
        ':telefono' => $telefono,
        ':notas'    => $notas === '' ? null : $notas,
        ':total'    => $totalCentavos / 100,
        ':latitud'  => $latitud,
        ':longitud' => $longitud,
        ':direccion' => $direccion === '' ? null : $direccion,
        ':fcm_token' => $fcmToken === '' ? null : $fcmToken,
        ':clave_idem' => $claveIdem === '' ? null : $claveIdem,
    ];
    // El índice UNIQUE de la base es quien garantiza que no se repita:
    // si el folio ya existe, MySQL rechaza el INSERT (error 1062) y se intenta con otro.
    for ($intento = 1; ; $intento++) {
        try {
            $stmt->execute($datos + [':folio' => generarFolio()]);
            break;
        } catch (PDOException $e) {
            $repetido = ($e->errorInfo[1] ?? null) === 1062;
            // Dos reintentos con la misma clave llegaron a la vez: el otro ya lo guardó
            if ($repetido && $claveIdem !== '' && strpos($e->getMessage(), 'uq_pedidos_idem') !== false) {
                $pdo->rollBack();
                responderSiYaExiste($pdo, $claveIdem);
            }
            if (!$repetido || $intento >= FOLIO_INTENTOS) {
                throw $e;
            }
        }
    }
    $pedidoId = (int)$pdo->lastInsertId();

    $stmt = $pdo->prepare(
        "INSERT INTO pedido_detalle (pedido_id, producto_id, nombre, precio_unitario, cantidad)
         VALUES (:pedido, :producto, :nombre, :precio, :cantidad)"
    );
    foreach ($cantidades as $id => $cant) {
        $stmt->execute([
            ':pedido'   => $pedidoId,
            ':producto' => $id,
            ':nombre'   => $productos[$id]['nombre'],
            ':precio'   => $productos[$id]['precio'],
            ':cantidad' => $cant,
        ]);
    }
    $pdo->commit();

    $stmt = $pdo->prepare("SELECT " . COLUMNAS_PEDIDO . " FROM pedidos WHERE id = ?");
    $stmt->execute([$pedidoId]);
    $pedidos = agregarRenglones($pdo, $stmt->fetchAll());

    responder(201, ['error' => false, 'pedido' => $pedidos[0]]);
}

/** Si ya hay un pedido con esa clave de idempotencia, lo responde (200) y termina. */
function responderSiYaExiste(PDO $pdo, string $claveIdem): void {
    $stmt = $pdo->prepare("SELECT " . COLUMNAS_PEDIDO . " FROM pedidos WHERE clave_idem = ?");
    $stmt->execute([$claveIdem]);
    $existente = $stmt->fetchAll();
    if (count($existente) > 0) {
        header('Idempotent-Replay: true');
        $pedidos = agregarRenglones($pdo, $existente);
        responder(200, ['error' => false, 'repetido' => true, 'pedido' => $pedidos[0]]);
    }
}

/** "CMD-" + caracteres al azar con random_int (generador seguro, no predecible). */
function generarFolio(): string {
    $folio = FOLIO_PREFIJO;
    $max = strlen(FOLIO_ALFABETO) - 1;
    for ($i = 0; $i < FOLIO_LONGITUD; $i++) {
        $folio .= FOLIO_ALFABETO[random_int(0, $max)];
    }
    return $folio;
}

// ---------------------------------------------
// GET: consultar por folio (una comanda) o por teléfono (los 20 más recientes)
// ---------------------------------------------
function consultarPedidos(PDO $pdo): void {
    if (isset($_GET['folio'])) {
        $folio = strtoupper(trim((string)$_GET['folio']));
        if (!preg_match(FOLIO_REGEX, $folio)) {
            error_api(422, 'El folio debe ser como CMD-7K3F9Q.');
        }
        $stmt = $pdo->prepare("SELECT " . COLUMNAS_PEDIDO . " FROM pedidos WHERE folio = :folio");
        $stmt->execute([':folio' => $folio]);
    } else {
        $telefono = trim((string)($_GET['telefono'] ?? ''));
        if (!preg_match('/^\d{10}$/', $telefono)) {
            error_api(422, 'Escribe un teléfono de 10 dígitos.');
        }
        $stmt = $pdo->prepare(
            "SELECT " . COLUMNAS_PEDIDO . "
             FROM pedidos
             WHERE telefono = :telefono
             ORDER BY id DESC
             LIMIT 20"
        );
        $stmt->execute([':telefono' => $telefono]);
    }
    $pedidos = agregarRenglones($pdo, $stmt->fetchAll());

    responder(200, ['error' => false, 'total' => count($pedidos), 'pedidos' => $pedidos]);
}
