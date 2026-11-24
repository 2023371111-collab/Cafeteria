-- =============================================
-- Notificación "pedido listo" al reloj (Firebase Cloud Messaging).
-- Ejecutar DESPUÉS de pedidos_folio.sql.
-- (Si creas las tablas desde cero con pedidos.sql y usuarios_app.sql, no hace falta ejecutar esto.)
-- Ejecutar en phpMyAdmin: base de datos -> pestaña "SQL" -> pegar -> Continuar
-- =============================================

-- Token FCM del teléfono que hizo el pedido: a ese teléfono se le avisa cuando esté listo.
-- NULL = el cliente no dio permiso de notificaciones (el pedido se guarda igual).
ALTER TABLE pedidos
    ADD COLUMN fcm_token VARCHAR(255) NULL;

-- Rol de la cuenta: solo un 'barista' puede marcar pedidos como listos.
-- Sesión: el servidor guarda el HASH del token que entrega login.php y cuándo vence.
ALTER TABLE usuarios_app
    ADD COLUMN rol          VARCHAR(10) NOT NULL DEFAULT 'cliente',
    ADD COLUMN token_hash   CHAR(64)    NULL,
    ADD COLUMN token_expira DATETIME    NULL,
    ADD INDEX idx_usuarios_token (token_hash);

-- La cuenta del operador es la del barista
UPDATE usuarios_app SET rol = 'barista' WHERE usuario = 'operador.qro';
