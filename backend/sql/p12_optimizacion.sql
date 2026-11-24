-- =============================================
-- Práctica 12 · Optimización. Ejecutar UNA vez sobre la base que YA existe,
-- ANTES de subir el nuevo pedidos.php.
-- phpMyAdmin: base de datos -> pestaña "SQL" -> pegar -> Continuar
-- =============================================

ALTER TABLE pedidos
    -- Clave de idempotencia: UUID que manda la app (encabezado Idempotency-Key).
    -- UNIQUE: la base garantiza que un reintento nunca cree un segundo pedido.
    -- NULL permitido (y repetible) para las peticiones sin clave.
    ADD COLUMN clave_idem CHAR(36) NULL,
    ADD UNIQUE INDEX uq_pedidos_idem (clave_idem),
    -- Pantalla del barista: WHERE estado IN ('recibido','listo') ORDER BY id.
    -- Sin este índice MySQL recorre toda la tabla (incluidos los entregados).
    ADD INDEX idx_pedidos_estado_id (estado, id);
