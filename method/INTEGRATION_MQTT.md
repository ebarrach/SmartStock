# MQTT — raccordement matériel en cours

Branche de travail : `integration/scala-akka-develop`. Sources matérielles reprises de `feature/scala-akka`, commit `05f9524`. Cette étape n’est pas une validation de la version fusionnée sur matériel, ni une clôture de l’intégration complète.

## Chemin canonique

`ServiceMain` conserve l’unique `InventoryActor`, le fichier d’inventaire atomique, les acteurs/services et l’API de `develop`. `HardwareMqtt` ajoute **un** client Paho et un abonnement. Aucun `SmartStockManager`, second serveur `SmartStockApi` ou fichier `stock.json`/`history.json` concurrent n’est démarré.

- Phidget : `code/raspberry/smartstock_phidget_mqtt.py`, température °C et humidité %, publications QoS 1 datées. Un UUID est maintenant créé avant publication ; les retransmissions Paho d’un même message conservent son contenu.
- MKR : `code/arduino/SmartStockMKR/SmartStockMKR.ino`, porte et HX711. Configuration locale `arduino_secrets.h` à créer depuis l’exemple, ignorée par Git. Client ID propre à chaque carte. WiFiNINA, PubSubClient et HX711 requis ; carte exacte à confirmer avant compilation/flash. Aucun flash effectué.
- Scanner : `code/raspberry/smartstock_qrbot_http.py` et `gs1_decoder.py`. QRbot → HTTP → décodage GS1 → MQTT. Port HTTP par défaut **8083**, distinct de l’API ; variable `SMARTSTOCK_QRBOT_PORT`. La réponse 202 confirme seulement la publication, jamais le changement de stock.
- Topic par défaut : `smartstock/events`, configurable par `SMARTSTOCK_MQTT_TOPIC`.

La perte/reprise du broker est persistée comme `connection.changed` pour `mqtt-broker` ; l’alerte `connection_lost` disparaît après réabonnement réussi. Les tentatives initiales échouées ne multiplient pas les changements d’état.

## Messages et garanties

Messages simples ou enveloppés dans `data` : `sensor=temperature|humidity|door|weight|sensor_status`. Température/humidité deviennent des `Reading` canoniques. Porte : `door.changed`, 1 ouverte / 0 fermée ; ce n’est pas un infrarouge. Statut : `sensor.failed`, 1 déconnecté / 0 connecté, source distinguée par capteur. Source et date UTC sont conservées si fournies ; sinon la date est celle de réception.

HX711 `unit=raw` : valeur et delta conservés comme `weight.raw` et `weight.delta.raw`, dans les lectures existantes, **sans conversion ni mise à jour du stock**. Ce sont deux observations diagnostiques, pas une transaction de stock. La fenêtre brute existante reste limitée aux 1000 dernières lectures.

Avec `id` UUID + `timestamp` stables, les répétitions passent par les reçus persistants existants. Sans ID producteur, un UUID de **livraison** est créé : il ne prouve ni l’unicité physique ni une association avec un scan. Les messages legacy identiques ne sont pas supprimés ; une retransmission non identifiable peut donc ajouter une observation diagnostique. Aucun stock n’en dépend. Les dates/identifiants invalides sont refusés, sans exposer les payloads dans les logs.

Les enveloppes **v1** déjà prévues par `code/shared/api/openapi.yaml` sont relayées vers `/api/v1/events` de l’API locale du même processus. Le traitement conserve ainsi validation, déduplication et `StockInputs`, sans recopier les règles :

```text
MQTT v1 → API locale → StockInputs → InventoryActor → inventaire persistant → API/mobile
```

Une opération nécessite **avant la mesure** un `operationId` partagé, des UUID d’événement distincts et stables, une source cohérente, des séquences distinctes, le même lot et une variation réellement exprimée en grammes. Deux opérations similaires ont des `operationId` différents. Scan et poids peuvent arriver dans les deux ordres, dans la fenêtre `SMARTSTOCK_CORRELATION_MS` (défaut 5000 ms). Le producteur doit réémettre les mêmes enveloppes depuis son outbox, pas recréer les UUID au réessai. La passerelle existante `code/gateway/emitter.py` fournit des UUID/séquences et une outbox durable ; elle ne sait pas deviner l’association matérielle manquante.

**Ancien protocole :** QRbot et le MKR d’origine n’échangent aucun début d’opération, accusé d’armement ou identifiant partagé. L’ancien workflow « dernier scan puis prochain poids » n’est pas réactivé. Le scan legacy est refusé explicitement par le pont pour le stock.

**Adaptation préparée, supervisée par l’opérateur :** le firmware de cette branche accepte maintenant sur Serial `ARM OPERATION_UUID CAPTURE_UUID`, refuse un chevauchement et annule après 90 s. Attendre `ARMED` avant mouvement ; un nouveau zéro de référence est pris à l’armement. Le premier mouvement porte les identifiants puis désarme. Sans armement il reste diagnostique. Ce changement n’est ni flashé ni validé sur carte ; il n’invente aucune calibration.

`correlated_capture.py --begin` crée les deux identifiants **avant** acquisition et une commande Serial à copier. Ajouter le même `operationId` au corps HTTP QRbot, qui le conserve dans son message. Enregistrer le scan et le poids du broker (fichiers JSON), et la date UTC réelle de réception du poids. Le convertisseur exige l’égalité des deux identifiants d’opération, le `captureId` du firmware, l’ID QRbot, un profil de calibration explicitement lié au `source` de la carte et une date avec fuseau. Il ne cherche jamais le dernier scan. L’opérateur reste responsable de l’association physique ; l’UUID seul ne la prouve pas.

```powershell
python code/raspberry/correlated_capture.py --begin --output SESSION_NOUVELLE.json
# Copier armCommand dans Serial, attendre ARMED ; scanner avec operationId de cette session.
# Capturer les messages réels, puis effectuer le mouvement dans la fenêtre autorisée.
python code/raspberry/correlated_capture.py --operation UUID_SESSION --scan SCAN_REEL.json --weight POIDS_REEL.json --calibration CALIBRATION_LOCALE.json --batch GTIN-LOT --snapshot INVENTAIRE_API.json --observed-at DATE_UTC_REELLE --output CAPTURE_NOUVELLE.jsonl
python code/raspberry/publish_capture.py CAPTURE_NOUVELLE.jsonl
```

Le convertisseur exige la convention de lot du collègue `GTIN-lot`, et contrôle le GTIN, l’armoire et la péremption contre un snapshot de l’API. Les anciens IDs arbitraires de `develop` restent intacts ; ils ne sont pas renommés ni devinés. Leur correspondance physique nécessitera une table vérifiée avant d’utiliser ce convertisseur. Télécharger le snapshot depuis `/api/v1/snapshot` en JSON UTF-8. Le produit doit avoir le GTIN comme `dataMatrix` et une masse unitaire réelle. Les captures doivent être datées avec fuseau et espacées d’au plus 90 s.

Le profil produit par `calibration.py` doit être complété avec `deviceSource` égal au `SECRET_MQTT_CLIENT_ID` de la carte. Le décalage est validé mais seule la pente s’applique à un delta ; conserver `SENS_POIDS=1` ou adapter explicitement la convention après mesure. Aucun profil par défaut, aucun remplacement de fichier existant. Le JSONL conserve les IDs pour réémission ; l’accusé broker ne prouve pas la mise à jour de stock, à vérifier dans l’API. La source v1 `capture-UUID` identifie la coordination explicite de cette opération, pas un capteur physique ; les sources physiques restent contrôlées dans les captures et la calibration.

Ce parcours est **supervisé**, pas une automatisation d’armement par MQTT. La capture et l’armement doivent être intégrés/recettés sur la carte avant utilisation opérationnelle. Le MKR conserve la publication QoS0 d’origine : une perte peut imposer une reprise explicite, jamais la fabrication d’un mouvement. Si le délai de corrélation est dépassé, réconcilier via le parcours de correction, ne pas rafraîchir artificiellement les captures. Pour une recette manuelle longue, configurer délibérément `SMARTSTOCK_CORRELATION_MS=90000` avant démarrage.

## Calibration

Outil trouvé : `code/gateway/calibration.py`, présent dans les deux branches et l’historique (`1ac1ff2`, `05f9524`). Il calcule `gramsPerRaw`, `offsetGrams` et un résidu à partir d’un CSV réel ; il ne constitue pas une calibration HX711 déjà mesurée. Coefficients physiques validés : **non trouvé dans les sources examinées**. Recherche : code, documents texte et historique de ces chemins sur les références develop/feature/anitatelle disponibles ; profils JSON/CSV locaux hors caches/dépendances/secrets. Ce périmètre n’est pas une preuve d’absence sur les postes du groupe ni dans toutes les archives externes. Aucun facteur fictif par défaut n’est utilisé.

Relever la tare stable à 0 g, puis des lectures stables avec au moins deux masses connues et des répétitions. Enregistrer `raw,grams`, avec les mêmes paramètres d’acquisition que le firmware ; vérifier le signe `SENS_POIDS` (actuellement 1) et l’identifiant de la carte. Pour des deltas, le décalage de tare s’annule mais pas le facteur ni son signe.

```powershell
python code/gateway/calibration.py MESURES_REELLES.csv CALIBRATION_LOCALE.json
```

Ce fichier n’est pas appliqué automatiquement au pont legacy : même une conversion correcte ne résout pas l’absence d’association avec le scan. Mesurer aussi les masses unitaires réelles utilisées dans les produits du catalogue ; une masse de démonstration ne doit pas devenir une référence matérielle.

## Lancement

Construire un JAR candidat distinct du service existant, depuis `code/smartstock-agent` :

```powershell
sbt test 'set assembly / assemblyCacheOutput := false' 'set assembly / assemblyOutputPath := file("../../carnet-de-bord/smartstock-mqtt-candidate.jar")' assembly
$env:SMARTSTOCK_MQTT_URL='tcp://127.0.0.1:1883'
$env:SMARTSTOCK_MQTT_TOPIC='smartstock/events'
$env:SMARTSTOCK_PORT='18112'
$env:SMARTSTOCK_DEMO_DATA='../../carnet-de-bord/mqtt-recette.json'
java -jar ../../carnet-de-bord/smartstock-mqtt-candidate.jar --demo
```

Définir `SMARTSTOCK_MQTT_USER` et `SMARTSTOCK_MQTT_PASSWORD` localement si le broker l’exige, jamais dans Git ou la conversation. Sans `SMARTSTOCK_MQTT_URL`, le service conserve le fonctionnement précédent sans MQTT. Connexion initiale réessayée toutes les 5 s ; reconnexion Paho ensuite, réabonnement après connexion et arrêt du client via le hook JVM. Vérifier un seul message d’abonnement par connexion dans les logs. Réutiliser uniquement un stockage distinct des services en cours.

Pour les producteurs Python : `python -m pip install -r code/raspberry/requirements.txt`, puis `python code/raspberry/smartstock_phidget_mqtt.py` ou `python code/raspberry/smartstock_qrbot_http.py`. Broker configurable par `SMARTSTOCK_MQTT_HOST`, `SMARTSTOCK_MQTT_PORT`, topic/utilisateur identiques ; saisie locale du mot de passe. SDK/driver Phidget et accès matériel requis.

## Recette physique restante

1. Stockage de recette isolé ; carte/Phidget/scanner identifiés. Vérifier 1 abonnement.
2. Modifier température/humidité : une lecture datée dans `/api/v1/readings`, même valeur dans le mobile ; ventilation logique et alertes sans doublon lié à un second moteur.
3. Ouvrir/fermer la porte : `door.changed` 1/0. Poser/retirer une masse : `weight.raw` et delta brut, **quantités inchangées** tant que le protocole corrélé n’est pas disponible.
4. Débrancher/rebrancher Phidget et couper/rétablir le broker : statut, reconnexion et reprise sans multiplier les consommateurs.
5. Scanner un GS1 valide : publication 202 seulement, aucun retrait implicite. Ne pas déclarer ce parcours terminé sur la seule réponse QRbot.
6. Après implémentation du protocole partagé et calibration réelle : opération explicite, scan/poids v1 cohérents, 40→39 ; replay mêmes UUID reste39 ; seconde opération similaire descend38 ; redémarrage conserve le journal. Ce dernier point est actuellement validé uniquement par producteur logiciel sur broker local.
