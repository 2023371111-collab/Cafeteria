<?php
// =============================================
// API: Productos
// GET  /api/productos.php          → lista todos
// GET  /api/productos.php?q=cafe   → búsqueda por nombre/descripción
//
// Práctica 12 (optimización): la lista completa casi no cambia y es la consulta más
// repetida (cada vez que alguien abre el menú). Se guarda en un caché de archivo durante
// CACHE_SEGUNDOS: mientras esté vigente se responde SIN abrir conexión a MySQL, que es el
// recurso que se satura en el hosting compartido. Las búsquedas (?q=) siguen yendo a la base.
// Encabezado X-Cache: HIT (desde caché) o MISS (desde la base) para poder medirlo.
// =============================================

header("Content-Type: application/json; charset=UTF-8");
header("Access-Control-Allow-Origin: *");           // Permite peticiones desde Android
header("Access-Control-Allow-Methods: GET, OPTIONS");

const CACHE_SEGUNDOS = 30;
const CACHE_ARCHIVO  = __DIR__ . '/../privado/cache/productos.json'; // privado/ no se puede descargar (.htaccess)

$metodo = $_SERVER['REQUEST_METHOD'] ?? 'GET';
if ($metodo === 'OPTIONS') {
    http_response_code(204);
    exit;
}

// Parámetro de búsqueda (opcional)
$busqueda = isset($_GET['q']) ? trim((string)$_GET['q']) : '';

// 1) Lista completa: intentar primero el caché
if ($busqueda === '') {
    $cache = @file_get_contents(CACHE_ARCHIVO);
    $edad  = $cache === false ? null : time() - (int)@filemtime(CACHE_ARCHIVO);
    if ($cache !== false && $edad !== null && $edad < CACHE_SEGUNDOS) {
        header('X-Cache: HIT');
        header('Cache-Control: public, max-age=' . (CACHE_SEGUNDOS - $edad));
        echo $cache;
        exit;
    }
}

require_once __DIR__ . '/../db.php';

try {
    $pdo = getConnection();

    if ($busqueda !== '') {
        // Búsqueda por nombre O descripción
        $stmt = $pdo->prepare(
            "SELECT id, nombre, descripcion, precio, imagen_url
             FROM productos
             WHERE disponible = 1
               AND (nombre LIKE :q_nombre OR descripcion LIKE :q_desc)
             ORDER BY nombre ASC"
        );
        // Con EMULATE_PREPARES = false no se puede repetir el mismo parámetro, por eso van dos
        $stmt->execute([
            ':q_nombre' => "%$busqueda%",
            ':q_desc'   => "%$busqueda%",
        ]);
    } else {
        // Sin filtro: todos los disponibles
        $stmt = $pdo->query(
            "SELECT id, nombre, descripcion, precio, imagen_url
             FROM productos
             WHERE disponible = 1
             ORDER BY nombre ASC"
        );
    }

    $productos = $stmt->fetchAll();
    $pdo = null; // libera la conexión antes de escribir la respuesta

    $json = json_encode([
        'error'     => false,
        'total'     => count($productos),
        'productos' => $productos
    ], JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT);

    if ($busqueda === '') {
        // Escritura atómica: se escribe un temporal y se renombra, así ninguna solicitud
        // lee un archivo a medias aunque varias lleguen al mismo tiempo.
        $dir = dirname(CACHE_ARCHIVO);
        if (!is_dir($dir)) {
            @mkdir($dir, 0750, true);
        }
        $tmp = CACHE_ARCHIVO . '.' . getmypid() . '.tmp';
        if (@file_put_contents($tmp, $json) !== false) {
            @rename($tmp, CACHE_ARCHIVO);
        }
        header('X-Cache: MISS');
    }
    echo $json;

} catch (PDOException $e) {
    http_response_code(500);
    echo json_encode([
        'error'   => true,
        'message' => 'Error al obtener los productos.'
    ], JSON_UNESCAPED_UNICODE);
}
