<?php
// =============================================
// Archivo de prueba de conexión
// IMPORTANTE: Elimina este archivo del servidor
// una vez que confirmes que funciona.
// =============================================

require_once __DIR__ . '/../db.php';

echo "<h2>🔌 Test de conexión - Cafetería DB</h2>";

try {
    $pdo = getConnection();

    // Si llega aquí, la conexión fue exitosa
    echo "<p style='color:green; font-size:18px;'>✅ Conexión exitosa a <strong>" . DB_NAME . "</strong> en <strong>" . DB_HOST . "</strong></p>";

    // Verificar que la tabla productos existe y tiene datos
    $stmt = $pdo->query("SELECT COUNT(*) AS total FROM productos");
    $result = $stmt->fetch();

    echo "<p>📦 Productos en la base de datos: <strong>" . $result['total'] . "</strong></p>";

    if ($result['total'] > 0) {
        $stmt = $pdo->query("SELECT id, nombre, precio FROM productos LIMIT 5");
        $productos = $stmt->fetchAll();

        echo "<table border='1' cellpadding='8' style='border-collapse:collapse;'>";
        echo "<tr><th>ID</th><th>Nombre</th><th>Precio</th></tr>";
        foreach ($productos as $p) {
            echo "<tr>
                    <td>{$p['id']}</td>
                    <td>{$p['nombre']}</td>
                    <td>\${$p['precio']}</td>
                  </tr>";
        }
        echo "</table>";
    } else {
        echo "<p style='color:orange;'>⚠️ La tabla existe pero está vacía. Ejecuta el script SQL con los INSERT.</p>";
    }

} catch (Exception $e) {
    echo "<p style='color:red;'>❌ Error: " . $e->getMessage() . "</p>";
}
