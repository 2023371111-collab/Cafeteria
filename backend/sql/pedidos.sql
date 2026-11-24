-- =============================================
-- Tablas para los pedidos de la Cafetería Móvil
-- Ejecutar en phpMyAdmin: base de datos -> pestaña "SQL" -> pegar -> Continuar
-- =============================================

-- Encabezado del pedido: quién lo hizo y cuánto fue en total
CREATE TABLE IF NOT EXISTS pedidos (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    -- Folio de la comanda que ve el cliente (ej. CMD-7K3F9Q). UNIQUE: nunca se repite.
    folio      CHAR(10)      NOT NULL,
    cliente   VARCHAR(60)   NOT NULL,
    telefono   CHAR(10)      NOT NULL,
    notas      VARCHAR(150)  NULL,
    total      DECIMAL(10,2) NOT NULL CHECK (total >= 0),
    estado     VARCHAR(20)   NOT NULL DEFAULT 'recibido',
    creado_en  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Ubicación de entrega opcional (NULL = se recoge en la cafetería)
    latitud    DECIMAL(9,6)  NULL CHECK (latitud BETWEEN -90 AND 90),
    longitud   DECIMAL(9,6)  NULL CHECK (longitud BETWEEN -180 AND 180),
    direccion  VARCHAR(150)  NULL,
    -- Token FCM del teléfono del cliente para avisarle cuando el pedido esté listo
    fcm_token  VARCHAR(255)  NULL,
    -- Práctica 12: clave de idempotencia (encabezado Idempotency-Key) para no duplicar reintentos
    clave_idem CHAR(36)      NULL,
    UNIQUE INDEX uq_pedidos_folio (folio),
    UNIQUE INDEX uq_pedidos_idem (clave_idem),
    INDEX idx_pedidos_telefono (telefono),
    INDEX idx_pedidos_estado_id (estado, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Renglones del pedido: qué productos y cuántos.
-- Se guarda una copia del nombre y el precio para que el pedido no cambie
-- aunque después se edite el menú.
CREATE TABLE IF NOT EXISTS pedido_detalle (
    id               INT AUTO_INCREMENT PRIMARY KEY,
    pedido_id        INT           NOT NULL,
    producto_id      INT           NOT NULL,
    nombre           VARCHAR(100)  NOT NULL,
    precio_unitario  DECIMAL(10,2) NOT NULL,
    cantidad         INT           NOT NULL CHECK (cantidad BETWEEN 1 AND 20),
    CONSTRAINT fk_detalle_pedido FOREIGN KEY (pedido_id)
        REFERENCES pedidos (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
