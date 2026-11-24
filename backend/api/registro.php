<?php
// =============================================
// API: Crear cuenta (práctica 5, AccesoSeguro)
// POST /api/registro.php   cuerpo JSON: {"nombre": "...", "usuario": "...", "contrasena": "..."}
//
// - Se validan las mismas reglas que en la app (nunca confiar en el cliente).
// - La contraseña se guarda como hash con password_hash() (bcrypt), nunca en texto plano.
// - Consultas preparadas contra inyección SQL.
// - Errores sin detalles técnicos; las credenciales nunca se escriben en logs.
// =============================================

header("Content-Type: application/json; charset=UTF-8");
header("Access-Control-Allow-Origin: *");
header("Access-Control-Allow-Methods: POST, OPTIONS");
header("Access-Control-Allow-Headers: Content-Type");
header("Cache-Control: no-store");

require_once __DIR__ . '/../db.php';

const MAX_USUARIO    = 30;
const MIN_CONTRASENA = 8;
const MAX_CONTRASENA = 64;

$metodo = $_SERVER['REQUEST_METHOD'];
if ($metodo === 'OPTIONS') {
    http_response_code(204);
    exit;
}
if ($metodo !== 'POST') {
    error_registro(405, 'Método no permitido.');
}

$datos = json_decode(file_get_contents('php://input'), true);
$nombre     = is_array($datos) && is_string($datos['nombre'] ?? null) ? trim(preg_replace('/\s+/u', ' ', $datos['nombre'])) : '';
$usuario    = is_array($datos) && is_string($datos['usuario'] ?? null) ? trim($datos['usuario']) : '';
$contrasena = is_array($datos) && is_string($datos['contrasena'] ?? null) ? $datos['contrasena'] : '';

// Mismas reglas que ValidadorAcceso.validarRegistro() en la app
if ($nombre === '' || $usuario === '' || $contrasena === '') {
    error_registro(400, 'Completa todos los campos.');
}
if (!preg_match("/^[\p{L} .'-]{3,60}$/u", $nombre)) {
    error_registro(400, 'El nombre solo puede tener letras (3 a 60).');
}
if (mb_strlen($usuario) < 4 || mb_strlen($usuario) > MAX_USUARIO || !preg_match('/^[A-Za-z0-9._-]+$/', $usuario)) {
    error_registro(400, 'Usuario no válido.');
}
if (mb_strlen($contrasena) < MIN_CONTRASENA || mb_strlen($contrasena) > MAX_CONTRASENA
    || !preg_match('/\p{L}/u', $contrasena) || !preg_match('/\d/', $contrasena)) {
    error_registro(400, 'La contraseña no cumple los requisitos.');
}

try {
    $pdo = getConnection();
    $stmt = $pdo->prepare("INSERT INTO usuarios_app (usuario, nombre, password_hash) VALUES (:usuario, :nombre, :hash)");
    $stmt->execute([
        ':usuario' => $usuario,
        ':nombre'  => $nombre,
        ':hash'    => password_hash($contrasena, PASSWORD_DEFAULT),
    ]);

    http_response_code(201);
    echo json_encode([
        'error'   => false,
        'usuario' => $usuario,
        'nombre'  => $nombre,
    ], JSON_UNESCAPED_UNICODE);

} catch (PDOException $e) {
    // 23000 = se violó el UNIQUE de usuario: ya existe una cuenta con ese nombre de usuario
    if ($e->getCode() === '23000') {
        error_registro(409, 'Ese usuario no está disponible. Elige otro.');
    }
    error_registro(500, 'No fue posible procesar la solicitud. Intenta más tarde.');
}

function error_registro(int $codigo, string $mensaje): void {
    http_response_code($codigo);
    echo json_encode(['error' => true, 'message' => $mensaje], JSON_UNESCAPED_UNICODE);
    exit;
}
