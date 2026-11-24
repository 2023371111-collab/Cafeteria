-- =============================================
-- Cuentas para iniciar sesión en la app (práctica 5, AccesoSeguro)
-- Ejecutar en phpMyAdmin: base de datos -> pestaña "SQL" -> pegar -> Continuar
-- =============================================

-- Nunca se guarda la contraseña: solo su hash (password_hash de PHP, bcrypt).
CREATE TABLE IF NOT EXISTS usuarios_app (
    id                 INT AUTO_INCREMENT PRIMARY KEY,
    usuario            VARCHAR(30)  NOT NULL UNIQUE,
    nombre             VARCHAR(60)  NOT NULL,
    password_hash      VARCHAR(255) NOT NULL,
    -- Bloqueo temporal después de varios intentos fallidos (fuerza bruta)
    intentos_fallidos  INT          NOT NULL DEFAULT 0,
    bloqueado_hasta    DATETIME     NULL,
    -- 'cliente' o 'barista' (solo el barista marca pedidos como listos)
    rol                VARCHAR(10)  NOT NULL DEFAULT 'cliente',
    -- Sesión: hash SHA-256 del token que entrega login.php y cuándo vence
    token_hash         CHAR(64)     NULL,
    token_expira       DATETIME     NULL,
    INDEX idx_usuarios_token (token_hash),
    creado_en          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Cuenta de prueba. La contraseña se entrega aparte; aquí solo va el hash.
-- Para crear otra cuenta, genera el hash con: php -r 'echo password_hash("LaContraseña", PASSWORD_DEFAULT);'
INSERT IGNORE INTO usuarios_app (usuario, nombre, rol, password_hash) VALUES
('operador.qro', 'Operador Querétaro', 'barista','$2y$10$nYHTAIYznBgs9KZq0pRr6OsaWMwcaRTThK.zcTQENGlWMODu0sdr.');
