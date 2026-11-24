-- =============================================
-- Datos de prueba FICTICIOS para el entorno de pruebas (no son datos reales).
-- Se cargan después de pedidos.sql y usuarios_app.sql.
-- =============================================
SET NAMES utf8mb4; -- sin esto el script se lee como latin1 y los acentos se corrompen

-- La tabla productos no tiene script en sql/ (se creó a mano en phpMyAdmin);
-- aquí se define con las columnas que leen productos.php y pedidos.php.
CREATE TABLE IF NOT EXISTS productos (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    nombre      VARCHAR(100)  NOT NULL,
    descripcion VARCHAR(255)  NULL,
    precio      DECIMAL(10,2) NOT NULL,
    imagen_url  VARCHAR(255)  NULL,
    disponible  TINYINT(1)    NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO productos (id, nombre, descripcion, precio, disponible) VALUES
(1, 'Americano de prueba',   'Café de prueba 12 oz',      35.00, 1),
(2, 'Capuchino de prueba',   'Café con leche de prueba',  48.50, 1),
(3, 'Pan de elote de prueba','Rebanada de prueba',        27.90, 1),
(4, 'Frappé descontinuado',  'Producto NO disponible',    55.00, 0);

-- Cuenta ficticia para la prueba E2E. Contraseña: PruebaE2E2026 (solo existe en este entorno)
INSERT INTO usuarios_app (usuario, nombre, rol, password_hash) VALUES
('cliente.e2e', 'Cliente Pruebas', 'cliente', '$2y$10$8ZA6.wA62.c27qfAz9.tHeMHyPNB4vHub.QYU88SkoP2zaZh9POkm');

-- Barista ficticio para probar la práctica 6 (cajones). Contraseña: BaristaE2E2026 (solo en este entorno)
INSERT INTO usuarios_app (usuario, nombre, rol, password_hash) VALUES
('barista.e2e', 'Barista Pruebas', 'barista', '$2y$10$DK2AHvV3QVCaE8rlILeybu1BgnHZDcXau.QB7XFwBLq28BfHV7CMe');
