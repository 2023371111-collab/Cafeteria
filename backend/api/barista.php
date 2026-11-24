<?php
// =============================================
// API: Pantalla del barista (solo cuentas con rol 'barista')
// Encabezado obligatorio: X-Sesion: <token que entregó login.php>
//
// GET  /api/barista.php   → pedidos pendientes (recibidos y listos), el más viejo primero
// POST /api/barista.php   cuerpo JSON: {"folio":"CMD-7K3F9Q","estado":"listo"}
//      recibido → listo      : se avisa al teléfono del cliente (y de ahí a su reloj) por FCM
//      listo    → entregado  : el pedido sale de la lista
// =============================================

header("Content-Type: application/json; charset=UTF-8");
header("Access-Control-Allow-Origin: *");
header("Access-Control-Allow-Methods: GET, POST, OPTIONS");
header("Access-Control-Allow-Headers: Content-Type, X-Sesion");
header("Cache-Control: no-store");

require_once __DIR__ . '/../comun.php';
require_once __DIR__ . '/../fcm.php';
require_once __DIR__ . '/../cajones.php';

const MAX_PENDIENTES = 50;
// Único cambio permitido desde cada estado: nunca se brinca ni se regresa
const SIGUIENTE_ESTADO = ['recibido' => 'listo', 'listo' => 'entregado'];

$metodo = $_SERVER['REQUEST_METHOD'];
if ($metodo === 'OPTIONS') {
    http_response_code(204);
    exit;
}

try {
    $pdo = getConnection();
    $cuenta = cuentaDeSesion($pdo);
    if ($cuenta['rol'] !== 'barista') {
        error_api(403, 'Esta sección es solo para el barista.');
    }

    if ($metodo === 'GET') {
        pendientes($pdo);
    } elseif ($metodo === 'POST') {
        cambiarEstado($pdo);
    } else {
        error_api(405, 'Método no permitido.');
    }
} catch (PDOException $e) {
    error_api(500, 'Error al procesar la solicitud. Intenta más tarde.');
}

function pendientes(PDO $pdo): void {
    $stmt = $pdo->prepare(
        "SELECT " . COLUMNAS_PEDIDO . ", fcm_token IS NOT NULL AS avisable
         FROM pedidos
         WHERE estado IN ('recibido', 'listo')
         ORDER BY id
         LIMIT " . MAX_PENDIENTES
    );
    $stmt->execute();
    $pedidos = agregarRenglones($pdo, $stmt->fetchAll());

    responder(200, ['error' => false, 'total' => count($pedidos), 'pedidos' => $pedidos]);
}

function cambiarEstado(PDO $pdo): void {
    $entrada = json_decode(file_get_contents('php://input'), true);
    $folio  = strtoupper(trim((string)($entrada['folio'] ?? '')));
    $estado = (string)($entrada['estado'] ?? '');

    if (!preg_match('/^CMD-[0-9A-Z]{6}$/', $folio)) {
        error_api(422, 'Folio no válido.');
    }
    $anterior = array_search($estado, SIGUIENTE_ESTADO, true);
    if ($anterior === false) {
        error_api(422, 'Estado no válido.');
    }

    // El WHERE con el estado anterior evita que dos baristas lo cambien a la vez
    $stmt = $pdo->prepare("UPDATE pedidos SET estado = :nuevo WHERE folio = :folio AND estado = :anterior");
    $stmt->execute([':nuevo' => $estado, ':folio' => $folio, ':anterior' => $anterior]);
    if ($stmt->rowCount() === 0) {
        error_api(409, "El pedido $folio no existe o ya no está \"$anterior\". Actualiza la lista.");
    }

    // Práctica 6: al entregar, el cajón de avisos del pedido se libera, baja al final
    // de la pila y lo toma el primer pedido en espera. Si las tablas de la práctica 6
    // aún no existen en la base, la entrega se guarda igual.
    if ($estado === 'entregado') {
        try {
            $pdo->beginTransaction();
            bloquearCajones($pdo);
            $id = $pdo->prepare("SELECT id FROM pedidos WHERE folio = ?");
            $id->execute([$folio]);
            liberarCajonDePedido($pdo, (int)$id->fetchColumn());
            asignarEspera($pdo);
            $pdo->commit();
        } catch (PDOException $e) {
            if ($pdo->inTransaction()) {
                $pdo->rollBack();
            }
        }
    }

    $stmt = $pdo->prepare("SELECT " . COLUMNAS_PEDIDO . ", fcm_token FROM pedidos WHERE folio = ?");
    $stmt->execute([$folio]);
    $pedido = $stmt->fetch();
    $fcmToken = $pedido['fcm_token'];
    unset($pedido['fcm_token']);

    // Aviso al cliente: solo al pasar a "listo"
    $notificacion = null;
    if ($estado === 'listo') {
        if ($fcmToken === null) {
            $notificacion = 'El cliente no activó las notificaciones.';
        } else {
            $error = enviarFcm($fcmToken, [
                'tipo'    => 'pedido_listo',
                'folio'   => $folio,
                'titulo'  => '¡Tu pedido está listo!',
                'mensaje' => "{$pedido['cliente']}, tu pedido $folio ya está listo. Pasa al mostrador.",
            ]);
            $notificacion = $error ?? 'enviada';
        }
    }

    $pedidos = agregarRenglones($pdo, [$pedido]);
    responder(200, ['error' => false, 'pedido' => $pedidos[0], 'notificacion' => $notificacion]);
}
