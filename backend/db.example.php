<?php
// =============================================
// Conexión a la base de datos MySQL
// Plantilla SIN credenciales (práctica 13): copiar como db.php y poner los datos del servidor.
// db.php real NO se sube al repositorio (.gitignore); el pipeline CI usa esta plantilla
// y recibe los datos del MySQL de pruebas por las variables CAFETERIA_DB_*.
// Las variables de entorno CAFETERIA_DB_* los reemplazan (las usa el entorno de pruebas, backend/pruebas).
// Práctica 12: nombres con prefijo propio. Con DB_* genéricos, las variables de entorno de OTRO
// proyecto del mismo hosting (CAS) hacían que esta API se conectara a la base equivocada.
// =============================================

define('DB_HOST',     getenv('CAFETERIA_DB_HOST') ?: 'localhost');              // En hosting compartido siempre es localhost
define('DB_PORT',     getenv('CAFETERIA_DB_PORT') ?: '3306');          // Puerto MySQL (default 3306)
define('DB_USER',     getenv('CAFETERIA_DB_USER') ?: 'TU_USUARIO');    // ej: root
define('DB_PASS',     getenv('CAFETERIA_DB_PASS') ?: 'TU_CONTRASENA'); // Tu contraseña
define('DB_NAME',     getenv('CAFETERIA_DB_NAME') ?: 'TU_BASE'); // Nombre de la base de datos

function getConnection(): PDO {
    $dsn = "mysql:host=" . DB_HOST
         . ";port=" . DB_PORT
         . ";dbname=" . DB_NAME
         . ";charset=utf8mb4";

    $options = [
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES   => false,
    ];

    // Práctica 12: el hosting compartido limita las conexiones simultáneas a MySQL
    // (errores 1040 "Too many connections" y 1203 "max_user_connections"). En vez de
    // fallar al primer rechazo se reintenta con espera progresiva y un poco de azar
    // (para que las solicitudes que chocaron no vuelvan a chocar al mismo tiempo).
    $esperasMs = [40, 80, 160, 320, 640];          // ~1.2 s como máximo en total
    for ($intento = 0; ; $intento++) {
        try {
            return new PDO($dsn, DB_USER, DB_PASS, $options);
        } catch (PDOException $e) {
            $codigo = (int)($e->errorInfo[1] ?? $e->getCode());
            // 1044/1045 credenciales y 1049 base inexistente: reintentar no sirve.
            // Todo lo demás (1040, 1203, 1226 límite de conexiones, 2002 socket ocupado...) es temporal.
            $saturado = !in_array($codigo, [1044, 1045, 1049], true);
            if ($saturado && $intento < count($esperasMs)) {
                usleep(($esperasMs[$intento] + random_int(0, 40)) * 1000);
                continue;
            }
            // Saturado después de reintentar: 503 (temporal, se puede reintentar) y no 500
            http_response_code($saturado ? 503 : 500);
            if ($saturado) {
                header('Retry-After: 1');
            }
            echo json_encode([
                'error'   => true,
                'message' => $saturado
                    ? 'El servidor está ocupado. Intenta de nuevo en un momento.'
                    : 'Error de conexión con la base de datos.'
            ], JSON_UNESCAPED_UNICODE);
            exit;
        }
    }
}
