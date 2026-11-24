<?php
// =============================================
// API: Inicio de sesión (práctica 5, AccesoSeguro)
// POST /api/login.php   cuerpo JSON: {"usuario": "...", "contrasena": "..."}
//
// Medidas de seguridad:
// - Solo POST y siempre por HTTPS (la contraseña nunca va en la URL).
// - Se vuelve a validar todo lo que valida la app (nunca confiar en el cliente).
// - Consultas preparadas: el usuario no puede inyectar SQL.
// - La contraseña se compara contra su hash con password_verify().
// - Un solo mensaje genérico para "no existe", "contraseña incorrecta" y "bloqueado",
//   así no se puede averiguar qué usuarios existen.
// - Bloqueo de 15 minutos después de 5 intentos fallidos.
// - Nunca se escriben las credenciales en logs ni en las respuestas.
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
const MAX_INTENTOS   = 5;
const MINUTOS_BLOQUEO = 15;
const MSG_INVALIDO   = 'Datos de acceso inválidos. Verifica e intenta de nuevo.';
const HORAS_SESION   = 12;  // el token de sesión vence después de este tiempo

$metodo = $_SERVER['REQUEST_METHOD'];
if ($metodo === 'OPTIONS') {
    http_response_code(204);
    exit;
}
if ($metodo !== 'POST') {
    error_login(405, 'Método no permitido.');
}

$datos = json_decode(file_get_contents('php://input'), true);
$usuario    = is_array($datos) && is_string($datos['usuario'] ?? null) ? trim($datos['usuario']) : '';
$contrasena = is_array($datos) && is_string($datos['contrasena'] ?? null) ? $datos['contrasena'] : '';

// Mismas reglas que la app
if ($usuario === '' || $contrasena === '') {
    error_login(400, 'Completa todos los campos.');
}
if (mb_strlen($usuario) > MAX_USUARIO
    || mb_strlen($contrasena) < MIN_CONTRASENA
    || mb_strlen($contrasena) > MAX_CONTRASENA
    || !preg_match('/^[A-Za-z0-9._-]+$/', $usuario)) {
    error_login(401, MSG_INVALIDO);
}

try {
    $pdo = getConnection();

    $stmt = $pdo->prepare(
        "SELECT id, nombre, rol, password_hash, intentos_fallidos,
                (bloqueado_hasta IS NOT NULL AND bloqueado_hasta > NOW()) AS bloqueado
         FROM usuarios_app WHERE usuario = :usuario"
    );
    $stmt->execute([':usuario' => $usuario]);
    $cuenta = $stmt->fetch();

    if (!$cuenta) {
        // Se calcula un hash de todas formas para que la respuesta tarde lo mismo
        // exista o no el usuario (evita adivinar usuarios por el tiempo de respuesta)
        password_verify($contrasena, '$2y$10$3yh/BQFBdmGQa1CuvpISseFeG8MP0MajtNB7luh7X.B3fETddJlFC');
        error_login(401, MSG_INVALIDO);
    }

    if ($cuenta['bloqueado']) {
        error_login(401, MSG_INVALIDO);
    }

    if (!password_verify($contrasena, $cuenta['password_hash'])) {
        $intentos = $cuenta['intentos_fallidos'] + 1;
        if ($intentos >= MAX_INTENTOS) {
            $pdo->prepare("UPDATE usuarios_app SET intentos_fallidos = 0,
                           bloqueado_hasta = DATE_ADD(NOW(), INTERVAL " . MINUTOS_BLOQUEO . " MINUTE) WHERE id = :id")
                ->execute([':id' => $cuenta['id']]);
        } else {
            $pdo->prepare("UPDATE usuarios_app SET intentos_fallidos = :n WHERE id = :id")
                ->execute([':n' => $intentos, ':id' => $cuenta['id']]);
        }
        error_login(401, MSG_INVALIDO);
    }

    // Acceso correcto: se reinicia el contador de intentos y se crea el token de sesión.
    // Al teléfono se le da el token; en la base solo queda su hash (si alguien lee la
    // tabla no puede usarlo). Un login nuevo invalida el token anterior.
    $token = bin2hex(random_bytes(32));
    $pdo->prepare("UPDATE usuarios_app SET intentos_fallidos = 0, bloqueado_hasta = NULL,
                   token_hash = :hash, token_expira = DATE_ADD(NOW(), INTERVAL " . HORAS_SESION . " HOUR)
                   WHERE id = :id")
        ->execute([':hash' => hash('sha256', $token), ':id' => $cuenta['id']]);

    echo json_encode([
        'error'   => false,
        'usuario' => $usuario,
        'nombre'  => $cuenta['nombre'],
        'rol'     => $cuenta['rol'],
        'token'   => $token,
    ], JSON_UNESCAPED_UNICODE);

} catch (PDOException $e) {
    // Sin detalles técnicos (ni la excepción, ni la consulta) en la respuesta
    error_login(500, 'No fue posible procesar la solicitud. Intenta más tarde.');
}

function error_login(int $codigo, string $mensaje): void {
    http_response_code($codigo);
    echo json_encode(['error' => true, 'message' => $mensaje], JSON_UNESCAPED_UNICODE);
    exit;
}
