-- =============================================
-- Agrega la ubicación de entrega a la tabla pedidos que YA existe.
-- (Si creas la tabla desde cero con pedidos.sql, no hace falta ejecutar esto.)
-- Ejecutar en phpMyAdmin: base de datos -> pestaña "SQL" -> pegar -> Continuar
-- =============================================

-- DECIMAL(9,6): 6 decimales ≈ 10 cm de precisión, de -180.000000 a 180.000000
ALTER TABLE pedidos
    ADD COLUMN latitud  DECIMAL(9,6) NULL CHECK (latitud BETWEEN -90 AND 90),
    ADD COLUMN longitud DECIMAL(9,6) NULL CHECK (longitud BETWEEN -180 AND 180);
