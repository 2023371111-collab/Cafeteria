<?php
// =============================================
// Envío de notificaciones con Firebase Cloud Messaging (API HTTP v1).
//
// Necesita la "cuenta de servicio" de Firebase:
//   Consola de Firebase -> ⚙ Configuración del proyecto -> Cuentas de servicio
//   -> "Generar nueva clave privada" -> se descarga un .json
// Ese archivo se sube a backend/privado/firebase-servicio.json (la carpeta tiene
// un .htaccess que impide descargarlo desde el navegador). NUNCA se sube a la app.
// =============================================

const FCM_CREDENCIALES = __DIR__ . '/privado/firebase-servicio.json';
const FCM_TIMEOUT      = 10;

/**
 * Manda un mensaje de datos al teléfono con ese token.
 * Es "data" (no "notification") para que la app lo reciba aunque esté cerrada
 * y pueda reenviarlo al reloj. Los valores de $datos deben ser texto.
 *
 * Regresa null si se envió, o el motivo por el que no se pudo.
 */
function enviarFcm(string $tokenDispositivo, array $datos): ?string {
    $cuenta = leerCuentaServicio();
    if ($cuenta === null) {
        return 'Falta configurar Firebase en el servidor.';
    }

    $acceso = tokenDeAccesoGoogle($cuenta);
    if ($acceso === null) {
        return 'Firebase rechazó las credenciales del servidor.';
    }

    $mensaje = [
        'message' => [
            'token'   => $tokenDispositivo,
            'data'    => array_map('strval', $datos),
            // HIGH: se entrega de inmediato aunque el teléfono esté en reposo
            'android' => ['priority' => 'HIGH'],
        ],
    ];
    [$codigo, $respuesta] = postHttp(
        'https://fcm.googleapis.com/v1/projects/' . $cuenta['project_id'] . '/messages:send',
        json_encode($mensaje),
        ['Content-Type: application/json', 'Authorization: Bearer ' . $acceso]
    );

    if ($codigo === 200) {
        return null;
    }
    // 404 UNREGISTERED: la app se desinstaló o el token cambió
    if ($codigo === 404 || $codigo === 400) {
        return 'El teléfono del cliente ya no está registrado para notificaciones.';
    }
    return "Firebase respondió con error ($codigo).";
}

function leerCuentaServicio(): ?array {
    if (!is_readable(FCM_CREDENCIALES)) {
        return null;
    }
    $cuenta = json_decode(file_get_contents(FCM_CREDENCIALES), true);
    $completa = is_array($cuenta)
        && isset($cuenta['project_id'], $cuenta['client_email'], $cuenta['private_key']);
    return $completa ? $cuenta : null;
}

/**
 * OAuth 2.0 con cuenta de servicio: se firma un JWT con la llave privada (RS256)
 * y Google lo cambia por un token de acceso que dura 1 hora.
 */
function tokenDeAccesoGoogle(array $cuenta): ?string {
    $ahora = time();
    $encabezado = base64url(json_encode(['alg' => 'RS256', 'typ' => 'JWT']));
    $datos = base64url(json_encode([
        'iss'   => $cuenta['client_email'],
        'scope' => 'https://www.googleapis.com/auth/firebase.messaging',
        'aud'   => 'https://oauth2.googleapis.com/token',
        'iat'   => $ahora,
        'exp'   => $ahora + 3600,
    ]));

    $firma = '';
    if (!openssl_sign("$encabezado.$datos", $firma, $cuenta['private_key'], OPENSSL_ALGO_SHA256)) {
        return null;
    }
    $jwt = "$encabezado.$datos." . base64url($firma);

    [$codigo, $respuesta] = postHttp(
        'https://oauth2.googleapis.com/token',
        http_build_query([
            'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
            'assertion'  => $jwt,
        ]),
        ['Content-Type: application/x-www-form-urlencoded']
    );
    $json = json_decode($respuesta, true);
    return $codigo === 200 && isset($json['access_token']) ? $json['access_token'] : null;
}

/** POST con cURL. Regresa [código HTTP, cuerpo]; código 0 si no hubo conexión. */
function postHttp(string $url, string $cuerpo, array $encabezados): array {
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_POST           => true,
        CURLOPT_POSTFIELDS     => $cuerpo,
        CURLOPT_HTTPHEADER     => $encabezados,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT        => FCM_TIMEOUT,
    ]);
    $respuesta = curl_exec($ch);
    $codigo = $respuesta === false ? 0 : (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    return [$codigo, $respuesta === false ? '' : $respuesta];
}

function base64url(string $datos): string {
    return rtrim(strtr(base64_encode($datos), '+/', '-_'), '=');
}
