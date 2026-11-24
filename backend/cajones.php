<?php
// =============================================
// Práctica 6: cajones reciclables A, B y C del barista.
// Lo usan mensajes.php (asignar / liberar) y barista.php (liberar al entregar).
//
// Reglas:
//  - Un pedido nuevo con aviso toma el primer cajón libre; si no hay, queda en espera.
//  - La lista de espera no tiene tabla: son los pedidos con avisos del cliente
//    que todavía no tienen cajón (mensajes.cajon IS NULL), por hora del primer aviso.
//  - Un cajón se libera al entregar el pedido, con "Liberar cajón" o tras 15 min sin avisos.
//  - Al liberarse, el cajón baja al final de la pila y el primero en espera lo toma.
// Todas las funciones que cambian cajones deben correr dentro de una transacción
// que antes haya bloqueado la tabla con bloquearCajones().
// =============================================

const MINUTOS_INACTIVIDAD = 15;

/** SELECT ... FOR UPDATE: mientras dure la transacción nadie más puede tomar un cajón. */
function bloquearCajones(PDO $pdo): array {
    return $pdo->query("SELECT letra, pedido_id FROM cajones ORDER BY letra FOR UPDATE")->fetchAll();
}

function liberarCajon(PDO $pdo, string $letra): void {
    $stmt = $pdo->prepare(
        "UPDATE cajones
         SET pedido_id = NULL, ocupado_desde = NULL, ultimo_aviso = NULL, liberado_en = NOW()
         WHERE letra = ? AND pedido_id IS NOT NULL"
    );
    $stmt->execute([$letra]);
}

/** Libera el cajón que ocupa ese pedido (si ocupa alguno). Lo llama barista.php al entregar. */
function liberarCajonDePedido(PDO $pdo, int $pedidoId): void {
    $stmt = $pdo->prepare(
        "UPDATE cajones
         SET pedido_id = NULL, ocupado_desde = NULL, ultimo_aviso = NULL, liberado_en = NOW()
         WHERE pedido_id = ?"
    );
    $stmt->execute([$pedidoId]);
}

/**
 * Libera los cajones inactivos (15 min sin avisos) o con el pedido ya entregado
 * y reparte los cajones libres a los pedidos en espera. Se ejecuta en cada petición,
 * así no hace falta un cron en el hosting.
 */
function mantenerCajones(PDO $pdo): void {
    $pdo->beginTransaction();
    bloquearCajones($pdo);
    $pdo->exec(
        "UPDATE cajones c
         LEFT JOIN pedidos p ON p.id = c.pedido_id
         SET c.pedido_id = NULL, c.ocupado_desde = NULL, c.ultimo_aviso = NULL, c.liberado_en = NOW()
         WHERE c.pedido_id IS NOT NULL
           AND (c.ultimo_aviso < NOW() - INTERVAL " . MINUTOS_INACTIVIDAD . " MINUTE
                OR p.estado = 'entregado')"
    );
    asignarEspera($pdo);
    $pdo->commit();
}

/** Pedidos en espera (sin cajón), el que avisó primero va primero. */
function listaEspera(PDO $pdo): array {
    return $pdo->query(
        "SELECT p.id, p.folio, p.cliente, MIN(m.creado_en) AS desde
         FROM mensajes m
         JOIN pedidos p ON p.id = m.pedido_id
         WHERE m.emisor = 'cliente' AND m.cajon IS NULL
           AND p.estado <> 'entregado'
           AND p.id NOT IN (SELECT pedido_id FROM cajones WHERE pedido_id IS NOT NULL)
         GROUP BY p.id, p.folio, p.cliente
         ORDER BY MIN(m.id)"
    )->fetchAll();
}

/**
 * Los cajones libres, en el orden de la pila: primero los que nunca se han usado
 * (A, B, C) y luego los que se liberaron hace más tiempo.
 */
function cajonesLibres(PDO $pdo): array {
    return $pdo->query(
        "SELECT letra FROM cajones
         WHERE pedido_id IS NULL
         ORDER BY liberado_en IS NOT NULL, liberado_en, letra"
    )->fetchAll(PDO::FETCH_COLUMN);
}

/** Mientras haya cajón libre y alguien esperando, el primero en espera toma el primer cajón libre. */
function asignarEspera(PDO $pdo): void {
    $libres = cajonesLibres($pdo);
    if (count($libres) === 0) {
        return;
    }
    foreach (listaEspera($pdo) as $i => $pedido) {
        if (!isset($libres[$i])) {
            break;
        }
        ocuparCajon($pdo, $libres[$i], (int)$pedido['id']);
    }
}

function ocuparCajon(PDO $pdo, string $letra, int $pedidoId): void {
    $stmt = $pdo->prepare(
        "UPDATE cajones SET pedido_id = ?, ocupado_desde = NOW(), ultimo_aviso = NOW()
         WHERE letra = ? AND pedido_id IS NULL"
    );
    $stmt->execute([$pedidoId, $letra]);
    // Los avisos que mandó mientras esperaba quedan atendidos en este cajón
    $stmt = $pdo->prepare("UPDATE mensajes SET cajon = ? WHERE pedido_id = ? AND cajon IS NULL");
    $stmt->execute([$letra, $pedidoId]);
}

/** Letra del cajón que ocupa el pedido, o null. */
function cajonDePedido(PDO $pdo, int $pedidoId): ?string {
    $stmt = $pdo->prepare("SELECT letra FROM cajones WHERE pedido_id = ?");
    $stmt->execute([$pedidoId]);
    $letra = $stmt->fetchColumn();
    return $letra === false ? null : $letra;
}

/** Posición en la lista de espera (1 = el siguiente), o null si no está esperando. */
function posicionEspera(PDO $pdo, int $pedidoId): ?int {
    foreach (listaEspera($pdo) as $i => $pedido) {
        if ((int)$pedido['id'] === $pedidoId) {
            return $i + 1;
        }
    }
    return null;
}
