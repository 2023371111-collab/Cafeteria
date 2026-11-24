<?php
// =============================================
// PRÁCTICA 12 · Benchmark de consultas (solo entorno de pruebas Docker, NO se sube al hosting)
// Ejecuta cada consulta N veces con PDO (igual que la API) y reporta ms por consulta.
//   docker exec pruebas-api-1 php /var/www/html/pruebas/bench_consultas.php <iteraciones>
// Para concurrencia se lanzan varios procesos a la vez (ver docs/practica12/pruebas/fase4_db.py).
// =============================================
require_once __DIR__ . '/../db.php';

$n = (int)($argv[1] ?? 200);
$cols = 'id, folio, cliente, telefono, notas, total, estado, creado_en, latitud, longitud, direccion';
$consultas = [
    'Q1' => "SELECT $cols FROM pedidos WHERE folio = 'CMD-S25000'",
    'Q2' => "SELECT $cols FROM pedidos WHERE telefono = '4420000123' ORDER BY id DESC LIMIT 20",
    'Q3' => "SELECT $cols FROM pedidos WHERE estado = 'entregado'",
    'Q3p' => "SELECT $cols FROM pedidos WHERE estado = 'entregado' AND id < 30000 ORDER BY id DESC LIMIT 50",
    'Q4' => "SELECT $cols FROM pedidos WHERE estado IN ('recibido', 'listo') ORDER BY id LIMIT 50",
    'Q5' => "SELECT COUNT(*) FROM pedidos WHERE estado = 'recibido'",
];
$pdo = getConnection();
$salida = [];
foreach ($consultas as $id => $q) {
    $veces = $id === 'Q3' ? max(1, intdiv($n, 10)) : $n;   // la grande trae 50 000 filas: menos repeticiones
    $t = [];
    for ($i = 0; $i < $veces; $i++) {
        $ini = hrtime(true);
        $filas = $pdo->query($q)->fetchAll();
        $t[] = (hrtime(true) - $ini) / 1e6;
    }
    sort($t);
    $salida[$id] = ['n' => $veces, 'prom_ms' => round(array_sum($t) / count($t), 3),
                    'p95_ms' => round($t[(int)ceil(0.95 * count($t)) - 1], 3), 'filas' => count($filas)];
}
echo json_encode($salida), "\n";
