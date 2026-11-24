-- =============================================
-- Agrega la dirección legible de la ubicación de entrega.
-- Ejecutar DESPUÉS de pedidos_ubicacion.sql.
-- (Si creas la tabla desde cero con pedidos.sql, no hace falta ejecutar esto.)
-- Ejecutar en phpMyAdmin: base de datos -> pestaña "SQL" -> pegar -> Continuar
-- =============================================

ALTER TABLE pedidos
    ADD COLUMN direccion VARCHAR(150) NULL AFTER longitud;
