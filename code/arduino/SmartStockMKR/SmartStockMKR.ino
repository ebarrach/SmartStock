#include <WiFiNINA.h>
#include <PubSubClient.h>
#include <HX711.h>

const char WIFI_SSID[] = "MON_WIFI";
const char WIFI_PASS[] = "MOT_DE_PASSE_WIFI";
const char MQTT_HOST[] = "192.168.129.134";
const int MQTT_PORT = 1883;
const char MQTT_USER[] = "smartstock_mqtt";
const char MQTT_PASS[] = "MOT_DE_PASSE_MQTT";
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
int reedBrut = HIGH;
int reedStable = HIGH;
unsigned long reedChangement = 0;

void publier(const char* payload) {
  if (mqtt.connected() && mqtt.publish(MQTT_TOPIC, payload)) {
    Serial.print("[MQTT] ");
    Serial.println(payload);
  } else {
    Serial.println("[MQTT] Publication impossible");
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
  char json[200];
  snprintf(json, sizeof(json),
    "{\"sensor\":\"weight\",\"event\":\"%s\",\"value\":%ld,\"delta\":%ld,\"unit\":\"raw\",\"source\":\"arduino-mkr\"}",
    direction, valeur, difference);
  publier(json);
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
    if (mqtt.connect("smartstock-mkr-weight", MQTT_USER, MQTT_PASS)) {
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
  mqtt.setBufferSize(256);
  Serial.println("SMARTSTOCK - POIDS + REED + MQTT");
}

void loop() {
  connecter();
  lirePorte();
  lirePoids();
  delay(10);
}
