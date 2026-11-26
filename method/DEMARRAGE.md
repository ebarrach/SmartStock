# SmartStock — démarrage local

Raccordement MQTT en cours sur la branche d’intégration : [INTEGRATION_MQTT.md](INTEGRATION_MQTT.md) (Phidget/MKR/QRbot, diagnostic brut et protocole de capture explicite). Ne pas assimiler la recette logicielle au test du matériel.

Commandes PowerShell depuis la racine du dépôt. Agent et mobile dans des terminaux séparés.

## 1. Agent/API — JDK 21 et sbt

```powershell
cd code/smartstock-agent
sbt assembly
java -jar target/scala-2.13/smartstock-agent.jar --demo
```

- `--demo` : initialise un stockage vide avec des données simulées ; omettre pour le mode sans initialisation fictive.
- Stockage réel : `code/smartstock-agent/data/inventory.json` ; avec `--demo` :
  `code/smartstock-agent/data/demo-inventory.json`.
- Autre stockage : définir `SMARTSTOCK_DATA` avant de lancer l’agent.
- Autre stockage de démo : `SMARTSTOCK_DEMO_DATA`, distinct de `SMARTSTOCK_DATA`.
- Catalogue : `config/catalog.json` (réel), `config/demo-catalog.json` (démo),
  depuis le dossier agent. `SMARTSTOCK_CATALOG` permet un fichier explicite.
  Une définition contient `product` (format API existant), `category`, `unit`,
  `targetQuantity`, `leadDays`, `packSize`, `coverageDays`, `safetyDays` et
  `storageConditions`. Les lots et leurs quantités restent dans le stockage.
  Le catalogue réel livré est vide ; il conserve les produits déjà enregistrés.
  Un catalogue marqué `exampleData: true` est refusé en mode réel.
- État : <http://127.0.0.1:8081/api/v1/health>.
- Stock et alertes : <http://127.0.0.1:8081/api/v1/snapshot>.
- Retrait et ventilation : moteur Scala/Akka local, sans service externe.

### Candidat sans arrêter le service existant

Depuis `code/smartstock-agent/`, JDK 21 :

```powershell
sbt 'set assembly / assemblyCacheOutput := false' 'set assembly / assemblyOutputPath := file("../../carnet-de-bord/smartstock-analysis-candidate.jar")' assembly
$env:SMARTSTOCK_PORT='18099'
$env:SMARTSTOCK_BIND='0.0.0.0'
$env:SMARTSTOCK_DEMO_DATA='../../carnet-de-bord/demo-telephone.json'
java -jar ../../carnet-de-bord/smartstock-analysis-candidate.jar --demo
```

Choisir un port libre et un fichier distinct pour chaque instance. Ne jamais partager le stockage entre deux processus. `--demo` utilise exclusivement des exemples ; pour le réel, omettre ce drapeau et définir `SMARTSTOCK_DATA` vers le stockage réel, avec une seule instance. Ne pas remplacer le JAR d’un service en cours.

Pour tester le candidat sur PC, depuis `code/mobile-app/` :

```powershell
$env:SMARTSTOCK_API_PROXY_TARGET='http://127.0.0.1:18099'
npm run web -- --port 5174
```

Ouvrir <http://127.0.0.1:5174> → Connexion → `/agent`. Sur téléphone : Connexion → `http://ADRESSE_IP_DU_PC:18099` (réseau local, port autorisé, HTTP seulement en développement), ou URL HTTPS déployée. L’émulateur utilise `http://10.0.2.2:18099`. Metro reste sur **8082**.

Graphiques : tableau de bord ou détail produit → produit, 3/6/12 mois, série et mois de capteurs. Suggestions : tableau de bord ou Stock → détail et actualisation, sans commande ni écriture de stock. Format JSON, formules et limites : [PREVISION.md](PREVISION.md).

## 2. Mobile — Node ≥ 22.12, npm, SDK Android

```powershell
cd code/mobile-app
npm ci
npm start
```

Dans un autre terminal, même dossier :

```powershell
npm run android
```

Metro utilise **8082**, l’API **8081** : les deux services peuvent tourner ensemble.

### Version PC / tablette dans le navigateur

Depuis `code/mobile-app/` :

```powershell
npm run web
```

Ouvrir <http://127.0.0.1:5173>. Dans l’en-tête → Connexion → Adresse de l’API,
entrer `/agent` et connecter. Le proxy Vite rejoint l’API locale sur 8081.
`npm run build:web` produit le build partagé dans `dist/`.

Sur téléphone Android en USB, si `adb` est installé :

```powershell
adb reverse tcp:8081 tcp:8081
```

L’adresse API dans l’application devient `http://127.0.0.1:8081`.
Sur réseau Wi-Fi, utiliser l’adresse HTTPS accessible du poste/Pi ; le bind
loopback par défaut ne permet pas un accès direct depuis un autre appareil.

Dans `code/mobile-app/app.json`, définir `apiBaseUrl` puis relancer l’application :

| Mode | Valeur |
| --- | --- |
| Données simulées locales | `null` |
| Émulateur Android, API du poste hôte | `"http://10.0.2.2:8081"` |
| Téléphone physique | URL HTTPS accessible de l’agent sur le réseau local |

Le transport HTTP de développement dépend de la configuration native Android.
Mode API : actualisation toutes les 5 s, bouton **Actualiser**, erreur visible et **Réessayer**.
iOS : macOS, Xcode et CocoaPods requis ; voir [mobile](../code/mobile-app/README.md).

## 3. Passerelle — Python 3

```powershell
python code/gateway/emitter.py --database "$env:TEMP/smartstock-events.sqlite" --demo
python code/gateway/emitter.py --database "$env:TEMP/smartstock-events.sqlite" --replay
```

Sans ces options : entrée JSONL sur stdin. [Déploiement Pi 3 Model B](../code/gateway/README.md).

## 4. Tests

```powershell
# Racine du dépôt
python -m pip install -r code/connectivity/requirements-test.txt
python -m unittest discover -s code/connectivity -v
python -m unittest discover -s code/gateway -v
```

Dans `code/smartstock-agent/` :

```powershell
sbt scalafmtCheckAll test assembly
```

Dans `code/mobile-app/` :

```powershell
npm run format:check
npm run lint -- --max-warnings 0
npm run typecheck
npm test -- --runInBand
```

Test complet API + moteur Scala/Akka, agent lancé avec `--demo` :

```powershell
python code/tools/integration_smoke.py --url http://127.0.0.1:8081
```

Ce test modifie le jeu de démonstration. Arrêt des services : `Ctrl+C` dans leurs terminaux.
Les capteurs, le BLE et la ventilation physique nécessitent une recette sur matériel.
Les guides officiels dans `doc/` seront rédigés par l’équipe en LaTeX.

## 5. Android autonome et Wi-Fi local

La release embarque JavaScript/Hermes, les ressources et le mode Démo. Metro n’est pas utilisé. L’adresse API et le choix Démo/API sont mémorisés sur l’appareil ; une panne reste une erreur, jamais un remplacement fictif. Délai réseau : 5 s ; nouvelle tentative automatique après chaque requête terminée, ou bouton Réessayer.

### Signature privée — à faire localement

`applicationId` conservé : `com.smartstockmobile`. L’ancienne configuration signait la release avec la clé debug ; ce repli est retiré. Sans clé release configurée, Gradle produit **app-release-unsigned.apk**, non installable. Aucun keystore release n’est fourni.

Si une clé release existe, la réutiliser. Sinon, depuis un terminal personnel (JDK 21), créer une clé hors du dépôt ; `keytool` demande les mots de passe sans les mettre dans la commande :

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE/.smartstock-signing"
keytool -genkeypair -v -keystore "$env:USERPROFILE/.smartstock-signing/smartstock-release.jks" -alias smartstock-release -keyalg RSA -keysize 3072 -validity 10000
```

Sauvegarder la clé et ses mots de passe dans un emplacement privé sûr. Ne pas les transmettre dans une conversation, un journal, Git ou un fichier source. La clé doit rester la même pour les mises à jour.

Depuis la racine, sélectionner **l’IP privée préférée affichée par le lanceur**, pas une adresse VPN/virtuelle ou déconnectée :

```powershell
$env:JAVA_HOME='C:/Users/esteb/.jdks/ms-21.0.12'
$env:ANDROID_HOME='C:/Users/esteb/AppData/Local/Android/Sdk'
./code/tools/Build-AndroidRelease.ps1 -Keystore "$env:USERPROFILE/.smartstock-signing/smartstock-release.jks" -Alias smartstock-release -LocalHttpHost 192.168.0.143
```

Le script demande les deux mots de passe par saisie masquée, les transmet au processus Gradle sans argument de ligne de commande et les retire ensuite. Ne pas activer de logs Gradle `--debug` ni de transcription du terminal pour la signature.

APK signé : `code/mobile-app/android/app/build/outputs/apk/release/app-release.apk`. Sans `-LocalHttpHost`, la release n’autorise que HTTPS. Avec ce paramètre, HTTP est autorisé pour **cette IPv4 privée exacte uniquement**, sans port dans l’exception Android ; l’URL saisie dans Connexion reste `http://IP:18099`. Aucun certificat HTTPS n’est désactivé. Si cette IP change, réserver l’adresse DHCP, reconstruire l’exception locale, ou passer à HTTPS. La configuration debug pour Metro n’entre pas dans la release.

### Backend Windows — deux stockages distincts

Le JAR candidat avec verrou de stockage est `carnet-de-bord/smartstock-wifi-candidate.jar`. Pour le reconstruire depuis `code/smartstock-agent/` sans remplacer le JAR courant :

```powershell
sbt 'set assembly / assemblyCacheOutput := false' 'set assembly / assemblyOutputPath := file("../../carnet-de-bord/smartstock-wifi-candidate.jar")' assembly
```

Puis depuis la racine, choisir **une seule** configuration :

```powershell
# Démo : fichier exclusivement destiné aux exemples
./code/tools/Start-SmartStock.ps1 -Mode Demo -Port 18099 -DataPath "$PWD/carnet-de-bord/wifi-demo.json" -JarPath "$PWD/carnet-de-bord/smartstock-wifi-candidate.jar"

# Réel : aucun exemple ; arrêter d’abord le service propriétaire de cet inventaire
./code/tools/Start-SmartStock.ps1 -Mode Real -Port 18099 -DataPath "$PWD/code/smartstock-agent/data/inventory.json" -JarPath "$PWD/carnet-de-bord/smartstock-wifi-candidate.jar" -CatalogPath "$PWD/code/smartstock-agent/config/catalog.json"
```

Le script refuse un port occupé, sépare les modes, affiche stockage/catalogue/URL et écoute sur `0.0.0.0`. Le JAR conserve un verrou exclusif `.lock` pendant toute son exécution. Ne jamais effacer ce verrou pour contourner un serveur actif. Les anciens JAR ne prennent pas ce verrou : vérifier et arrêter leur instance avant de réutiliser leur stockage. Par prudence, le lanceur Réel refuse le démarrage si un processus Java SmartStock est déjà détecté. Aucune donnée existante n’est purgée ou réinitialisée. Ctrl+C arrête le candidat.

### Pare-feu — commande à examiner, non appliquée automatiquement

Téléphone et PC sur le même réseau de confiance. Vérifier `Get-NetConnectionProfile`. La règle suivante ne s’applique qu’au profil **Privé**, à l’adresse locale choisie, au port TCP et au sous-réseau local. Adapter l’interface/IP, puis exécuter en administrateur seulement après validation :

```powershell
New-NetFirewallRule -DisplayName 'SmartStock LAN 18099' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 18099 -LocalAddress 192.168.0.143 -InterfaceAlias 'Ethernet 2' -RemoteAddress LocalSubnet -Profile Private
```

Ne pas modifier le pare-feu global, le profil réseau ou le routeur automatiquement. Aucun transfert de port, tunnel public ni publication Internet. Un réseau invité avec isolation Wi-Fi peut empêcher la communication.

### Installation et recette physique

```powershell
# Vérifier la signature, avec le SDK Android installé
& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs code/mobile-app/android/app/build/outputs/apk/release/app-release.apk
adb install -r code/mobile-app/android/app/build/outputs/apk/release/app-release.apk
```

**Si l’application installée est signée debug**, Android refusera sa mise à jour par la nouvelle clé (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Ne pas désinstaller ni effacer les données automatiquement. Relever la connexion et sauvegarder les données locales nécessaires via Android Studio Device Explorer pour l’application debug avant toute décision. L’inventaire et son historique sont sur le backend ; ne pas supprimer ses fichiers. `allowBackup=false` : ne pas compter sur une restauration automatique Android. Une migration avec désinstallation nécessite une action explicite de l’utilisateur. Aucune vérification Google Play n’a été effectuée : aucune publication envisagée ici.

1. Installer l’APK signé ; arrêter Metro.
2. `adb reverse --list`, puis retirer seulement les redirections SmartStock : `adb reverse --remove tcp:8082` (et `tcp:18099` / `tcp:8081` si présentes).
3. Débrancher l’USB, ouvrir l’application ; la Démo doit fonctionner hors ligne.
4. Connexion → `http://IP_LOCALE_DU_PC:18099` → connecter ; consulter Stock, Graphiques et Suggestions.
5. Fermer complètement et rouvrir : même adresse et même mode API, sans Metro.
6. Arrêter l’API : erreur/délai visibles, aucune donnée fictive. Relancer sur le même stockage après arrêt complet : reprise automatique ou Réessayer.

La compilation et l’inspection du bundle ne remplacent pas cette recette sur téléphone. Pour le futur Raspberry Pi 3 Model B, conserver la même API ; adapter l’adresse, le bind et les chemins de lancement. Une URL HTTPS stable évite de reconstruire l’exception HTTP locale ; aucune refonte d’écran nécessaire.

Références : [release React Native](https://reactnative.dev/docs/signed-apk-android), [configuration réseau Android](https://developer.android.com/privacy-and-security/security-config), [stockage local AsyncStorage](https://react-native-async-storage.github.io/3.0/api/usage/).

Icônes, écran de démarrage, image Play et build AAB : [ANDROID_DISTRIBUTION.md](ANDROID_DISTRIBUTION.md). Le helper accepte `-Target Apk`, `-Target Bundle` ou `-Target Both` ; pour l’AAB destiné à des testeurs hors LAN, omettre `-LocalHttpHost` et prévoir une API HTTPS réellement accessible. Aucun envoi à Google Play n’est effectué.
