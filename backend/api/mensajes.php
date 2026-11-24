<?php
// =============================================
// API: Práctica 6 (MobileConnect). Avisos entre el cliente (Dispositivo A) y el barista (Dispositivo B).
// No es un chat abierto: cada aviso va ligado a un pedido y se organiza en 3 cajones (A, B, C).
//
// CLIENTE (sin sesión; demuestra que es su pedido con el teléfono con el que pidió):
//   GET  mensajes.php?folio=CMD-7K3F9Q&telefono=4421234567[&desde=15]
//        → su conversación, su cajón o su posición en la espera
//   POST mensajes.php  {"folio":"CMD-7K3F9Q","telefono":"4421234567","emisor":"cliente","texto":"..."}
//
// BARISTA (encabezado X-Sesion con el token de una cuenta rol 'barista'):
//   GET  mensajes.php?cajones=1             → la pila de cajones y la lista de espera
//   GET  mensajes.php?folio=CMD-7K3F9Q      → la conversación de ese pedido
//   POST mensajes.php  {"folio":"CMD-7K3F9Q","emisor":"barista","texto":"..."}
//   POST mensajes.php  {"accion":"liberar","letra":"A"}
// =============================================

header("Content-Type: application/json; charset=UTF-8");
header("Access-Control-Allow-Origin: *");
header("Access-Control-Allow-Methods: GET, POST, OPTIONS");
header("Access-Control-Allow-Headers: Content-Type, X-Sesion");
header("Cache-Control: no-store");

require_once __DIR__ . '/../comun.php';
require_once __DIR__ . '/../cajones.php';

const MAX_TEXTO    = 100;   // caracteres por aviso
const MAX_MENSAJES = 200;   // tope de la conversación que se regresa
const FOLIO_VALIDO = '/^CMD-[0-9A-Z]{6}$/';

$metodo = $_SERVER['REQUEST_METHOD'];
if ($metodo === 'OPTIONS') {
    http_response_code(204);
    exit;
}

try {
    $pdo = getConnection();
    // Antes de contestar: libera cajones inactivos y reparte los libres a quien espera
    mantenerCajones($pdo);

    if ($metodo === 'GET') {
        consultar($pdo);
    } elseif ($metodo === 'POST') {
        $entrada = json_decode(file_get_contents('php://input'), true);
        if (!is_array($entrada)) {
            error_api(400, 'El cuerpo de la petición debe ser JSON válido.');
        }
        if (($entrada['accion'] ?? '') === 'liberar') {
            exigirBarista($pdo);
            liberar($pdo, $entrada);
        } else {
            enviar($pdo, $entrada);
        }
    } else {
        error_api(405, 'Método no permitido.');
    }
} catch (PDOException $e) {
    if (isset($pdo) && $pdo->inTransaction()) {
        $pdo->rollBack();
    }
    error_api(500, 'Error al procesar el aviso. Intenta más tarde.');
}

// ---------------------------------------------
// Quién hace la petición
// ---------------------------------------------

/** Solo una cuenta con rol barista ve y libera cajones (control de rol). */
function exigirBarista(PDO $pdo): array {
    $cuenta = cuentaDeSesion($pdo);
    if ($cuenta['rol'] !== 'barista') {
        error_api(403, 'Solo el barista puede ver y liberar los cajones.');
    }
    return $cuenta;
}

/** El pedido de ese folio. El cliente además debe dar el teléfono con el que pidió. */
function pedidoDe(PDO $pdo, string $folio, ?string $telefono): array {
    $folio = strtoupper(trim($folio));
    if (!preg_match(FOLIO_VALIDO, $folio)) {
        error_api(422, 'Folio no válido.');
    }
    $stmt = $pdo->prepare("SELECT id, folio, cliente, telefono, estado FROM pedidos WHERE folio = ?");
    $stmt->execute([$folio]);
    $pedido = $stmt->fetch();
    // Mismo mensaje si no existe o si el teléfono no coincide: no se revela qué folios existen
    if (!$pedido || ($telefono !== null && !hash_equals($pedido['telefono'], $telefono))) {
        error_api(404, 'No hay un pedido activo con ese folio y teléfono.');
    }
    return $pedido;
}

// ---------------------------------------------
// GET
// ---------------------------------------------

function consultar(PDO $pdo): void {
    if (isset($_GET['cajones'])) {
        exigirBarista($pdo);
        responder(200, ['error' => false] + pilaCajones($pdo));
    }

    $folio = (string)($_GET['folio'] ?? '');
    if (isset($_SERVER['HTTP_X_SESION'])) {
        exigirBarista($pdo);
        $pedido = pedidoDe($pdo, $folio, null);
    } else {
        $pedido = pedidoDe($pdo, $folio, (string)($_GET['telefono'] ?? ''));
    }
    $desde = max(0, (int)($_GET['desde'] ?? 0));
    responder(200, ['error' => false] + conversacion($pdo, $pedido, $desde));
}

/** Avisos del pedido (solo los nuevos si se manda "desde"), su cajón o su lugar en la espera. */
function conversacion(PDO $pdo, array $pedido, int $desde = 0): array {
    $stmt = $pdo->prepare(
        "SELECT id, emisor, texto, cajon, creado_en FROM mensajes
         WHERE pedido_id = ? AND id > ?
         ORDER BY id
         LIMIT " . MAX_MENSAJES
    );
    $stmt->execute([$pedido['id'], $desde]);
    $id = (int)$pedido['id'];
    return [
        'pedido'   => ['folio' => $pedido['folio'], 'cliente' => $pedido['cliente'], 'estado' => $pedido['estado']],
        'cajon'    => cajonDePedido($pdo, $id),
        'espera'   => posicionEspera($pdo, $id),
        'mensajes' => $stmt->fetchAll(),
    ];
}

/** Los 3 cajones como pila: arriba los ocupados (pedido más antiguo primero), abajo los libres. */
function pilaCajones(PDO $pdo): array {
    $cajones = $pdo->query(
        "SELECT c.letra, c.ocupado_desde, c.ultimo_aviso,
                p.folio, p.cliente, p.estado,
                GREATEST(0, " . MINUTOS_INACTIVIDAD . " - TIMESTAMPDIFF(MINUTE, c.ultimo_aviso, NOW())) AS minutos_restantes,
                (SELECT COUNT(*) FROM mensajes m WHERE m.pedido_id = c.pedido_id) AS total_mensajes,
                (SELECT CONCAT(m.emisor, ': ', m.texto) FROM mensajes m
                  WHERE m.pedido_id = c.pedido_id ORDER BY m.id DESC LIMIT 1) AS ultimo_mensaje
         FROM cajones c
         LEFT JOIN pedidos p ON p.id = c.pedido_id
         ORDER BY c.pedido_id IS NULL,          -- ocupados arriba
                  c.ocupado_desde,              -- del más antiguo al más reciente
                  c.liberado_en IS NOT NULL,    -- libres: los nunca usados primero
                  c.liberado_en, c.letra"
    )->fetchAll();

    foreach ($cajones as &$cajon) {
        $cajon['libre'] = $cajon['folio'] === null;
    }
    unset($cajon);

    $espera = array_map(
        fn($p) => ['folio' => $p['folio'], 'cliente' => $p['cliente'], 'desde' => $p['desde']],
        listaEspera($pdo)
    );
    return ['cajones' => $cajones, 'espera' => $espera];
}

// ---------------------------------------------
// POST
// ---------------------------------------------

/** Quita espacios de más; regresa null si el texto queda vacío, es muy largo o trae caracteres raros. */
function limpiarTexto(mixed $texto): ?string {
    $texto = trim(preg_replace('/\s+/u', ' ', (string)$texto));
    if ($texto === '' || mb_strlen($texto) > MAX_TEXTO || preg_match('/[\x00-\x1F\x7F]/', $texto)) {
        return null;
    }
    return $texto;
}

function enviar(PDO $pdo, array $entrada): void {
    $emisor = (string)($entrada['emisor'] ?? '');
    if (!in_array($emisor, ['cliente', 'barista'], true)) {
        error_api(422, 'El emisor debe ser cliente o barista.');
    }
    // Validación de mensajes vacíos también en el servidor (la app ya desactiva el botón)
    $texto = limpiarTexto($entrada['texto'] ?? '');
    if ($texto === null) {
        error_api(422, 'El aviso no puede ir vacío ni pasar de ' . MAX_TEXTO . ' caracteres.');
    }

    if ($emisor === 'barista') {
        exigirBarista($pdo);
        $pedido = pedidoDe($pdo, (string)($entrada['folio'] ?? ''), null);
    } else {
        $pedido = pedidoDe($pdo, (string)($entrada['folio'] ?? ''), (string)($entrada['telefono'] ?? ''));
    }
    if ($pedido['estado'] === 'entregado') {
        error_api(409, 'El pedido ya fue entregado: ya no admite avisos nuevos.');
    }
    $pedidoId = (int)$pedido['id'];

    // La transacción con los cajones bloqueados evita que dos clientes tomen el mismo cajón
    $pdo->beginTransaction();
    bloquearCajones($pdo);

    $letra = cajonDePedido($pdo, $pedidoId);
    if ($emisor === 'barista' && $letra === null) {
        $pdo->rollBack();
        error_api(409, 'Ese pedido no está en ningún cajón. Solo se contesta a los pedidos en cajón.');
    }

    $stmt = $pdo->prepare("INSERT INTO mensajes (pedido_id, folio, emisor, texto, cajon) VALUES (?, ?, ?, ?, ?)");
    $stmt->execute([$pedidoId, $pedido['folio'], $emisor, $texto, $letra]);

    if ($letra !== null) {
        $pdo->prepare("UPDATE cajones SET ultimo_aviso = NOW() WHERE letra = ?")->execute([$letra]);
    } else {
        // Sin cajón: toma el primero libre, o queda en espera (respetando a los que llegaron antes)
        asignarEspera($pdo);
    }
    $pdo->commit();

    responder(201, ['error' => false, 'message' => 'Aviso enviado'] + conversacion($pdo, $pedido));
}

function liberar(PDO $pdo, array $entrada): void {
    $letra = strtoupper(trim((string)($entrada['letra'] ?? '')));
    if (!in_array($letra, ['A', 'B', 'C'], true)) {
        error_api(422, 'Cajón no válido.');
    }
    $pdo->beginTransaction();
    bloquearCajones($pdo);
    liberarCajon($pdo, $letra);
    // El cajón liberado lo toma el primero en espera
    asignarEspera($pdo);
    $pdo->commit();

    responder(200, ['error' => false, 'message' => "Cajón $letra liberado"] + pilaCajones($pdo));
}
