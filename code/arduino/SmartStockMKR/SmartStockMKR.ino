#include <WiFiNINA.h>
#include <PubSubClient.h>
#include <HX711.h>
#include <ctype.h>
#include <string.h>

#include "arduino_secrets.h"
const char WIFI_SSID[] = SECRET_WIFI_SSID;
const char WIFI_PASS[] = SECRET_WIFI_PASS;
const char MQTT_HOST[] = SECRET_MQTT_HOST;
const int MQTT_PORT = SECRET_MQTT_PORT;
const char MQTT_USER[] = SECRET_MQTT_USER;
const char MQTT_PASS[] = SECRET_MQTT_PASS;
const char MQTT_TOPIC[] = "smartstock/events";

const int HX_DT = 2;
const int HX_SCK = 3;
const int REED_PIN = 5;
const int REED_OUVERT = HIGH;
const int SENS_POIDS = 1;
const long SEUIL_MOUVEMENT = 500;
const long TOLERANCE_STABLE = 400;
const unsigned long TEMPS_STABLE = 1500;

HX711 balance;
WiFiClient wifi;
PubSubClient mqtt(wifi);

long reference = 0;
bool referencePrete = false;
long mesures[8];
int position = 0;
int compte = 0;
bool candidat = false;
long valeurCandidate = 0;
unsigned long debutStable = 0;
unsigned long wifiEssai = 0;
unsigned long mqttEssai = 0;
unsigned long dernierAffichage = 0;
char operationId[37] = "";
char captureId[37] = "";
char commande[85] = "";
byte commandePosition = 0;
bool commandeOverflow = false;
unsigned long operationDebut = 0;
const unsigned long OPERATION_TTL = 90000;

// Explicit operator arming BEFORE movement. Never infer an operation from the last scan.
void lireOperation() {
  if (operationId[0] && millis() - operationDebut >= OPERATION_TTL) {
    operationId[0] = 0;
    captureId[0] = 0;
    Serial.println("[OPERATION] Expiree, aucune association implicite");
  }
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\r') continue;
    if (c != '\n') {
      if (!commandeOverflow && commandePosition < sizeof(commande) - 1) commande[commandePosition++] = c;
      else commandeOverflow = true;
      continue;
    }
    if (commandeOverflow) {
      commandePosition = 0; commandeOverflow = false;
      Serial.println("[OPERATION] Commande trop longue refusee");
      continue;
    }
    commande[commandePosition] = 0;
    bool valide = commandePosition == 77 && strncmp(commande, "ARM ", 4) == 0 && commande[40] == ' ';
    for (int part = 0; valide && part < 2; part++) {
      for (int i = 0; valide && i < 36; i++) {
        char v = commande[i + (part == 0 ? 4 : 41)];
        valide = (i == 8 || i == 13 || i == 18 || i == 23) ? v == '-' : isxdigit((unsigned char)v);
      }
    }
    if (valide && !operationId[0] && balance.is_ready() && mqtt.connected()) {
      // Capture a fresh baseline only after explicit arming; caller waits for ARMED.
      reference = balance.read_average(12);
      referencePrete = true;
      compte = 0;
      position = 0;
      candidat = false;
      memcpy(operationId, commande + 4, 36);
      operationId[36] = 0;
      memcpy(captureId, commande + 41, 36);
      captureId[36] = 0;
      operationDebut = millis();
      Serial.print("[OPERATION] ARMED "); Serial.println(operationId);
    } else Serial.println("[OPERATION] Refusee (commande, operation active ou capteur indisponible)");
    commandePosition = 0;
  }
}
int reedBrut = HIGH;
int reedStable = HIGH;
unsigned long reedChangement = 0;

void publier(const char* payload) {
  if (mqtt.connected() && mqtt.publish(MQTT_TOPIC, payload)) {
    Serial.print("[MQTT] ");
    Serial.println(payload);
  } else {
    Serial.println("[MQTT] Publication impossible");
    Serial.print("[CAPTURE NON LIVREE] "); Serial.println(payload);
  }
}

void envoyerPorte() {
  const char* etat = reedStable == REED_OUVERT ? "open" : "closed";
  char json[140];
  snprintf(json, sizeof(json), "{\"sensor\":\"door\",\"value\":\"%s\",\"source\":\"arduino-mkr\"}", etat);
  publier(json);
}

void lirePorte() {
  int lecture = digitalRead(REED_PIN);
  unsigned long now = millis();
  if (lecture != reedBrut) {
    reedBrut = lecture;
    reedChangement = now;
  }
  if (reedBrut != reedStable && now - reedChangement >= 70) {
    reedStable = reedBrut;
    envoyerPorte();
  }
}

void envoyerPoids(const char* direction, long valeur, long difference) {
  char json[384];
  snprintf(json, sizeof(json),
    "{\"sensor\":\"weight\",\"event\":\"%s\",\"value\":%ld,\"delta\":%ld,\"rawDelta\":%ld,\"unit\":\"raw\",\"source\":\"%s\",\"operationId\":\"%s\",\"captureId\":\"%s\"}",
    direction, valeur, difference, valeur - reference, SECRET_MQTT_CLIENT_ID, operationId, captureId);
  publier(json);
  operationId[0] = 0; // One movement per armed operation. QoS0 loss requires explicit operator recovery.
  captureId[0] = 0;
}

void lirePoids() {
  if (!balance.wait_ready_timeout(1000)) {
    Serial.println("[HX711] Indisponible");
    return;
  }

  if (!referencePrete) {
    reference = balance.read_average(12);
    referencePrete = true;
    Serial.print("[HX711] Reference = ");
    Serial.println(reference);
    return;
  }

  long valeur = balance.read_average(3);
  mesures[position] = valeur;
  position = (position + 1) % 8;
  if (compte < 8) compte++;
  if (compte < 8) return;

  long minimum = mesures[0];
  long maximum = mesures[0];
  long somme = 0;
  for (int i = 0; i < 8; i++) {
    if (mesures[i] < minimum) minimum = mesures[i];
    if (mesures[i] > maximum) maximum = mesures[i];
    somme += mesures[i];
  }

  long moyenne = somme / 8;
  bool stable = maximum - minimum <= TOLERANCE_STABLE;
  long difference = (moyenne - reference) * SENS_POIDS;
  unsigned long now = millis();

  if (stable && labs(difference) >= SEUIL_MOUVEMENT) {
    if (!candidat || labs(moyenne - valeurCandidate) > TOLERANCE_STABLE) {
      candidat = true;
      valeurCandidate = moyenne;
      debutStable = now;
    }
    if (now - debutStable >= TEMPS_STABLE) {
      envoyerPoids(difference > 0 ? "entry" : "exit", moyenne, difference);
      reference = moyenne;
      candidat = false;
    }
  } else {
    candidat = false;
  }

  if (now - dernierAffichage >= 1000) {
    dernierAffichage = now;
    // Periodic physical samples for server-side calibration; never a stock operation.
    char telemetry[192];
    snprintf(telemetry, sizeof(telemetry),
      "{\"sensor\":\"weight\",\"event\":\"sample\",\"value\":%ld,\"delta\":0,\"unit\":\"raw\",\"source\":\"%s\"}",
      moyenne, SECRET_MQTT_CLIENT_ID);
    publier(telemetry);
    Serial.print("[POIDS] ");
    Serial.print(valeur);
    Serial.print(" | Reference ");
    Serial.println(reference);
  }
}

void connecter() {
  unsigned long now = millis();
  if (WiFi.status() != WL_CONNECTED) {
    if (wifiEssai == 0 || now - wifiEssai >= 10000) {
      wifiEssai = now;
      Serial.println("[WIFI] Connexion...");
      WiFi.begin(WIFI_SSID, WIFI_PASS);
    }
    return;
  }

  if (!mqtt.connected() && (mqttEssai == 0 || now - mqttEssai >= 5000)) {
    mqttEssai = now;
    if (mqtt.connect(SECRET_MQTT_CLIENT_ID, MQTT_USER, MQTT_PASS)) {
      Serial.println("[MQTT] Connecte");
      publier("{\"sensor\":\"sensor_status\",\"name\":\"arduino_wifi\",\"status\":\"connected\"}");
      envoyerPorte();
    } else {
      Serial.print("[MQTT] Echec : ");
      Serial.println(mqtt.state());
    }
  }

  if (mqtt.connected()) mqtt.loop();
}

void setup() {
  Serial.begin(115200);
  delay(2000);
  pinMode(REED_PIN, INPUT_PULLUP);
  reedBrut = digitalRead(REED_PIN);
  reedStable = reedBrut;
  balance.begin(HX_DT, HX_SCK);
  mqtt.setServer(MQTT_HOST, MQTT_PORT);
  mqtt.setBufferSize(512);
  Serial.println("SMARTSTOCK - POIDS + REED + MQTT");
}

void loop() {
  connecter();
  lireOperation();
  lirePorte();
  lirePoids();
  delay(10);
}
