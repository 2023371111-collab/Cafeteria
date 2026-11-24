<?php
// =============================================
// API: Información del sitio (práctica 3, ConsultaWeb)
// GET /api/info.php → datos generales de la cafetería en JSON
// La app lo consume como "URL preasignada".
// =============================================

header("Content-Type: application/json; charset=UTF-8");
header("Access-Control-Allow-Origin: *");
header("Access-Control-Allow-Methods: GET, OPTIONS");

require_once __DIR__ . '/../db.php';

$info = [
    'error'      => false,
    'sitio'      => 'Cafetería Móvil',
    'mensaje'    => 'Bienvenido a la extensión web de la Cafetería Móvil.',
    'horario'    => 'Lunes a viernes, 7:00 a 20:00',
    'ubicacion'  => 'Universidad Tecnológica de Querétaro',
    'servidor'   => $_SERVER['SERVER_NAME'] ?? 'desconocido',
    'hora_servidor' => date('Y-m-d H:i:s'),
];

// Dato en vivo de la base: cuántos productos hay disponibles
try {
    $pdo = getConnection();
    $info['productos_disponibles'] = (int) $pdo->query("SELECT COUNT(*) FROM productos WHERE disponible = 1")->fetchColumn();
} catch (PDOException $e) {
    $info['productos_disponibles'] = null;
}

echo json_encode($info, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT);
