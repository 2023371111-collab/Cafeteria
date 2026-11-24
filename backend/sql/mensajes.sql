-- =============================================
-- Práctica 6: Interacción entre dispositivos (MobileConnect)
-- Avisos breves entre el cliente (Dispositivo A) y el barista (Dispositivo B),
-- siempre ligados a un pedido, organizados en 3 cajones reciclables (A, B y C).
-- Ejecutar DESPUÉS de pedidos.sql / pedidos_folio.sql.
-- Ejecutar en phpMyAdmin: base de datos -> pestaña "SQL" -> pegar -> Continuar
-- =============================================

-- Historial de avisos. Nunca se borran: quedan como evidencia del pedido.
CREATE TABLE IF NOT EXISTS mensajes (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    pedido_id  INT          NOT NULL,
    folio      CHAR(10)     NOT NULL,
    -- Quién lo mandó: el cliente (Dispositivo A) o el barista (Dispositivo B)
    emisor     ENUM('cliente', 'barista') NOT NULL,
    texto      VARCHAR(100) NOT NULL,
    -- Cajón en el que se atendió. NULL = el cliente lo mandó sin cajón libre (está en espera).
    -- La lista de espera sale de aquí: pedidos con avisos del cliente con cajon NULL.
    cajon      CHAR(1)      NULL,
    creado_en  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_mensajes_pedido (pedido_id, id),
    CONSTRAINT fk_mensajes_pedido FOREIGN KEY (pedido_id) REFERENCES pedidos(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Los 3 cajones de atención del barista. La letra es del espacio, no del pedido:
-- el mismo cajón atiende varios pedidos, uno tras otro.
CREATE TABLE IF NOT EXISTS cajones (
    letra          CHAR(1)  NOT NULL PRIMARY KEY,
    -- NULL = libre. UNIQUE: un pedido nunca ocupa dos cajones a la vez.
    pedido_id      INT      NULL,
    ocupado_desde  DATETIME NULL,
    -- Último aviso en el cajón: tras 15 min sin avisos se libera solo
    ultimo_aviso   DATETIME NULL,
    -- Al liberarse el cajón baja al final de la pila (orden por esta columna)
    liberado_en    DATETIME NULL,
    UNIQUE INDEX uq_cajones_pedido (pedido_id),
    CONSTRAINT fk_cajones_pedido FOREIGN KEY (pedido_id) REFERENCES pedidos(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO cajones (letra) VALUES ('A'), ('B'), ('C');
