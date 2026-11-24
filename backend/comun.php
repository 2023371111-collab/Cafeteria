<?php
// =============================================
// Funciones que comparten pedidos.php y barista.php
// =============================================

require_once __DIR__ . '/db.php';

// fcm_token NO va aquí: nunca se le regresa a nadie
const COLUMNAS_PEDIDO = 'id, folio, cliente, telefono, notas, total, estado, creado_en, latitud, longitud, direccion';

/** Agrega a cada pedido su lista "items" con los productos comprados. */
function agregarRenglones(PDO $pdo, array $pedidos): array {
    if (count($pedidos) === 0) {
        return $pedidos;
    }
    $ids    = array_column($pedidos, 'id');
    $marcas = implode(',', array_fill(0, count($ids), '?'));
    $stmt   = $pdo->prepare(
        "SELECT pedido_id, producto_id, nombre, precio_unitario, cantidad
         FROM pedido_detalle
         WHERE pedido_id IN ($marcas)
         ORDER BY id"
    );
    $stmt->execute($ids);

    $porPedido = [];
    foreach ($stmt->fetchAll() as $renglon) {
        $pedidoId = $renglon['pedido_id'];
        unset($renglon['pedido_id']);
        $porPedido[$pedidoId][] = $renglon;
    }
    foreach ($pedidos as &$pedido) {
        $pedido['items'] = $porPedido[$pedido['id']] ?? [];
    }
    unset($pedido);
    return $pedidos;
}

/**
 * Cuenta dueña del token de sesión que manda la app en el encabezado X-Sesion
 * (se usa un encabezado propio porque algunos hostings borran "Authorization").
 * Corta con 401 si falta, no existe o ya venció.
 */
function cuentaDeSesion(PDO $pdo): array {
    $token = (string)($_SERVER['HTTP_X_SESION'] ?? '');
    if (!preg_match('/^[a-f0-9]{64}$/', $token)) {
        error_api(401, 'Tu sesión expiró. Vuelve a iniciar sesión.');
    }
    $stmt = $pdo->prepare(
        "SELECT id, usuario, nombre, rol FROM usuarios_app
         WHERE token_hash = :hash AND token_expira > NOW()"
    );
    $stmt->execute([':hash' => hash('sha256', $token)]);
    $cuenta = $stmt->fetch();
    if (!$cuenta) {
        error_api(401, 'Tu sesión expiró. Vuelve a iniciar sesión.');
    }
    return $cuenta;
}

function responder(int $codigo, array $datos): void {
    http_response_code($codigo);
    echo json_encode($datos, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT);
    exit;
}

function error_api(int $codigo, string $mensaje): void {
    responder($codigo, ['error' => true, 'message' => $mensaje]);
}
