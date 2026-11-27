# Android autonome et serveur PC sur le réseau local

Le scanner, l'enregistrement produit et le bip restent inchangés. Pas de QRbot, de câble ou de Metro pendant l'utilisation.

## 1. Construire le serveur

```powershell
cd D:\Projects\Java\SmartStock\code\smartstock-agent
sbt assembly
```

## 2. Démarrer le PC

```powershell
cd D:\Projects\Java\SmartStock
.\code\tools\Start-PhoneServer.ps1 -Java 'C:\Users\esteb\.jdks\ms-21.0.12\bin\java.exe'
```

Garder ce terminal ouvert. Une seule instance par dossier. Le lanceur refuse un port déjà occupé et affiche le dossier et les fichiers persistants. Les données courantes sont les JSON dédiés sous **`D:\Projects\Java\SmartStock\code\data`** ; ne pas recréer de fixture ni lancer une ancienne copie du serveur. Le lanceur du carnet est seulement une redirection vers ce lanceur durable.

Le profil `Demo` d'origine est conservé pour les données migrées, y compris vos fiches saisies. Il ne réinjecte rien au démarrage et ne remplace jamais une erreur API par des données fictives. Pour un autre stockage, choisir explicitement `-DataDirectory` et le profil ; aucune fusion implicite. Structure, transaction, migration et retour arrière : [STOCKAGE_JSON.md](STOCKAGE_JSON.md).

Adresse LAN actuelle : **`http://192.168.0.143:18119`** (Ethernet 2). Téléphone en Wi-Fi sur le même routeur. Le Wi-Fi du PC était déconnecté : `192.168.0.3` est une ancienne adresse. Vérifier les URL affichées si le DHCP change.

Profil Windows Privé et règle `SmartStock-Local-18119` déjà appliqués avec accord utilisateur. Vérifier sans recréer :

```powershell
Get-NetConnectionProfile
Get-NetFirewallRule -Name 'SmartStock-Local-18119'
```

Règle limitée à Java, TCP18119, `LocalSubnet`, profil Privé ; aucune redirection de routeur, aucune désactivation globale du pare-feu. Sur un autre PC, examiner et autoriser explicitement ces réglages avant application.

## 3. APK autonome de test local

```powershell
cd D:\Projects\Java\SmartStock\code\mobile-app\android
$env:JAVA_HOME='C:\Users\esteb\.jdks\ms-21.0.12'
$env:ANDROID_HOME='C:\Users\esteb\AppData\Local\Android\Sdk'
$env:Path="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
.\gradlew.bat :app:assembleStandalone --no-daemon --max-workers=2 --console=plain
adb devices -l
adb install -r .\app\build\outputs\apk\standalone\app-standalone.apk
adb reverse --remove tcp:8082
adb reverse --remove tcp:18119
adb shell am force-stop com.smartstockmobile
adb shell am start -n com.smartstockmobile/.MainActivity
```

Arrêter Metro (Ctrl+C dans son terminal), débrancher l'USB. Dans **Connexion**, choisir API, saisir **`http://192.168.0.143:18119`**, connecter puis actualiser.

Le variant `standalone` embarque le bundle et les ressources, désactive le débogage et utilise la même clé locale que l'installation debug pour la mettre à jour sans effacer ses données. **APK de test local, pas une signature de distribution.** `applicationId` et signature release existante sont conservés. Si la signature de votre installation diffère, arrêter et examiner le conflit ; ne pas désinstaller pour le contourner.

Les adresses IPv4 locales sont configurables sans reconstruire l’APK. Android autorise HTTP au niveau applicatif ; le client réseau React Native filtre les destinations à l’exécution (RFC1918 : 10/8, 172.16/12, 192.168/16, et loopback127/8), y compris les redirections. HTTP public et noms DNS HTTP sont refusés ; HTTPS conserve la vérification standard des certificats et du nom d’hôte. HTTP reste non chiffré, même sur le LAN. La règle XML seule ne limite pas HTTP aux sous-réseaux privés ; c’est le filtrage applicatif/natif qui le fait pour les appels réseau SmartStock. Une nouvelle bibliothèque native utilisant son propre client devra appliquer la même politique.

## 4. Paramètres et recette

- **Connexion / paramètres du serveur → Mode sans capteurs** : choix partagé et persistant dans les JSON transactionnels. Activé : parcours de scan validé, ajout/retrait selon l'état unitaire, choix explicite si présence inconnue, aucune mesure fabriquée. Désactivé : scan reçu/en attente, puis événement matériel calibré portant la même opération/source/lot. Aucune panne ne réactive ce mode.
- Changer de mode invalide les anciennes corrélations sans effacer l'inbox, les quantités, les séries ou l'historique. Recommencer un scan volontaire après changement ; les retries d'une opération confirmée restent idempotents.
- **Boîte USB** : archivage ciblé de `usb-test` / GTIN `05400711223344`. Masquée du stock, des alertes et des analyses ; fiche, lots et mouvements conservés. Aucune suppression des autres produits.
- Les alertes de stock et péremption affichent le nom du produit ; les alertes environnementales gardent la source du capteur.
- Vérifier : scan → ajout → nouveau scan → retrait ; fermer/rouvrir l'application ; arrêter/reprendre l'API, sans données fictives ; redémarrer le serveur et vérifier le même mode.

## 5. Calibration physique

1. Démarrer les producteurs/broker existants. La balance doit publier au moins trois `weight.raw` récents de la **même source** en dix secondes. Une valeur zéro n'est pas une déconnexion.
2. Dans les paramètres, sélectionner la balance détectée. Vider le plateau et attendre des mesures stables ; **capturer la tare**.
3. Poser une masse connue, saisir sa masse réelle en grammes, attendre au moins trois nouvelles mesures stables ; **confirmer et calibrer**.
4. Vérifier indépendamment une autre masse connue, le retour à zéro et le signe ajout/retrait. La formule enregistrée est `grammes = (raw - tareRaw) × referenceGrams / (loadedRaw - tareRaw)`. Pour une variation : `deltaGrammes = deltaRaw × gramsPerRaw`. Aucune calibration fictive par défaut, aucun mouvement pendant la calibration.
5. Le firmware source MKR prépare une publication `event: sample, delta: 0, unit: raw` chaque seconde pour cette recette ; ce sont des mesures, jamais des mouvements. Cette modification n'est **pas flashée**. L'ancien firmware publiant seulement les variations peut ne pas fournir assez de points : calibration alors explicitement indisponible.
6. Le parcours normal exige aussi une masse unitaire réelle renseignée pour le produit, et un producteur armé avec l'opération/source/lot du scan. Pour le scanner mobile, envoyer sur le port série `ARM <operationId> <operationId>` puis attendre `ARMED` avant le mouvement. Le firmware préparé publie le `rawDelta` natif ; l’adaptateur MQTT exige les deux identifiants et retrouve le scan exact par opération et source `capture-UUIDsansTirets`. Le captureId est un UUID v4 unique par opération et devient l’identifiant stable du poids. Les anciens messages sans ces garanties restent des diagnostics. Si nécessaire pour la recette physique manuelle, régler explicitement `SMARTSTOCK_CORRELATION_MS=90000` avant le lancement ; le défaut reste 5000 ms. Aucun appariement au dernier scan.

`POST /api/v1/events` accepte `weight.changed` avec `grams`, ou avec **`rawDelta` + `deviceSource`** exclusivement. Cette dernière variante exige la calibration enregistrée de cette balance et conserve raw, grammes et identifiant de calibration dans l'inbox. `rawDelta` doit être la différence **native** des valeurs HX711 ; ne pas lui appliquer une seconde inversion `SENS_POIDS`. Sans identifiant partagé, aucun mouvement même après calibration. Aucun essai physique de balance n'est revendiqué.

Réglages : `GET /api/v1/settings`, `PUT /api/v1/settings/sensors`, `POST /api/v1/calibration/tare`, `POST /api/v1/calibration/confirm`. Écritures avec `X-Command-Id` UUID, même ID pour un retry. Réglages et calibration sont conservés atomiquement dans la même transaction que l'inventaire. Les anciens flags ne servent qu'à initialiser un stockage sans réglage ; ensuite le réglage persistant prévaut.

## Arrêter

Ctrl+C dans le terminal du serveur. Aucune commande de suppression de données. Pour retirer la règle locale créée manuellement : `Remove-NetFirewallRule -DisplayName 'SmartStock LAN 18119'`. Pour revenir au fonctionnement matériel : désactiver **Mode sans capteurs**, configurer les producteurs et effectuer la recette physique.

Vérification logicielle isolée : `python code/connectivity/wifi_settings_smoke.py --jar code/smartstock-agent/target/scala-2.13/smartstock-agent.jar --java C:/Users/esteb/.jdks/ms-21.0.12/bin/java.exe`. Ce test crée son stockage temporaire et utilise des mesures synthétiques, sans balance physique.
