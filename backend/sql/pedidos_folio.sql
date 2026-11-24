-- =============================================
-- Agrega el folio alfanumérico de la comanda (ej. CMD-7K3F9Q) a la tabla pedidos que YA existe.
-- Ejecutar DESPUÉS de pedidos_direccion.sql.
-- (Si creas la tabla desde cero con pedidos.sql, no hace falta ejecutar esto.)
-- Ejecutar en phpMyAdmin: base de datos -> pestaña "SQL" -> pegar -> Continuar
-- =============================================

-- 1. La columna se crea vacía para poder llenar los pedidos anteriores
ALTER TABLE pedidos
    ADD COLUMN folio CHAR(10) NULL AFTER id;

-- 2. Pedidos anteriores: folio a partir de su id en base 36 (ej. id 3 -> CMD-000003).
--    Como cada id es distinto, estos folios tampoco se repiten.
UPDATE pedidos
    SET folio = CONCAT('CMD-', LPAD(CONV(id, 10, 36), 6, '0'))
    WHERE folio IS NULL;

-- 3. Desde aquí el folio es obligatorio y la base rechaza cualquier repetido
ALTER TABLE pedidos
    MODIFY folio CHAR(10) NOT NULL,
    ADD UNIQUE INDEX uq_pedidos_folio (folio);
