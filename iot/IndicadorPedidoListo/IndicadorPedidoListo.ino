// =============================================
// Practica 1 - Firmware: Indicador de "Pedido listo"
// Cafeteria Movil
//
// El barista presiona el boton del mostrador cuando un pedido
// esta listo para entregarse; mientras lo mantiene presionado,
// el LED "PEDIDO LISTO" se enciende para avisar al cliente.
//
// Conexiones (ESP32):
//   LED     -> GPIO 2  (LED integrado de la placa, o LED externo + resistencia 220 ohm a GND)
//   Boton   -> GPIO 4  y el otro extremo a GND (se usa la resistencia pull-up interna)
// =============================================

const int LED_PEDIDO_LISTO = 2;
const int BOTON_BARISTA = 4;

void setup() {

  pinMode(LED_PEDIDO_LISTO, OUTPUT);
  pinMode(BOTON_BARISTA, INPUT_PULLUP);

  Serial.begin(115200);

  Serial.println("Cafeteria Movil - Indicador de pedido listo");
  Serial.println("Firmware iniciado");
}

void loop() {
  // Con INPUT_PULLUP el pin lee LOW cuando el boton esta presionado
  int estadoBoton = digitalRead(BOTON_BARISTA);

  if (estadoBoton == LOW) {
    digitalWrite(LED_PEDIDO_LISTO, HIGH);
    Serial.println("BOTON PRESIONADO - PEDIDO LISTO PARA ENTREGAR (LED ENCENDIDO)");
  } else {
    digitalWrite(LED_PEDIDO_LISTO, LOW);
    Serial.println("BOTON LIBERADO - PEDIDO EN PREPARACION (LED APAGADO)");
  }

  delay(500);
}
