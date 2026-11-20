# SmartStock — démarrage local

Commandes PowerShell depuis la racine du dépôt. Agent et mobile dans des terminaux séparés.

## 1. Agent/API — JDK 21 et sbt

```powershell
cd code/smartstock-agent
sbt assembly
java -jar target/scala-2.13/smartstock-agent.jar --demo
```

- `--demo` : initialise un stockage vide avec des données simulées ; omettre pour le mode sans initialisation fictive.
- Stockage : `code/smartstock-agent/data/inventory.json` avec ces commandes.
- Autre stockage : définir `SMARTSTOCK_DATA` avant de lancer l’agent.
- État : <http://127.0.0.1:8081/api/v1/health>.
- Stock et alertes : <http://127.0.0.1:8081/api/v1/snapshot>.
- Retrait et ventilation : moteur Scala/Akka local, sans service externe.

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
