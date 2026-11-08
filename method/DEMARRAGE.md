# SmartStock — démarrage local

Commandes PowerShell depuis la racine du dépôt. Trois terminaux séparés.

## 1. Anemone — JDK 8

Prérequis : `java` et `javac` du JDK 8 dans le `PATH`, JAR Anemone fourni par l’équipe.

```powershell
cd code/anemone-bridge
$env:ANEMONE_JAR='D:/chemin/vers/root-assembly-0.1.0-SNAPSHOT.jar'
./bridge/start.ps1 -Port 8092
```

## 2. Agent/API — JDK 21 et sbt

```powershell
cd code/smartstock-agent
$env:SMARTSTOCK_ANEMONE_URL='http://127.0.0.1:8092'
sbt assembly
java -jar target/scala-2.13/smartstock-agent.jar --demo
```

- `--demo` : initialise un stockage vide avec des données simulées ; omettre pour le mode sans initialisation fictive.
- Stockage : `code/smartstock-agent/data/inventory.json` avec ces commandes.
- Autre stockage : définir `SMARTSTOCK_DATA` avant de lancer l’agent.
- État : <http://127.0.0.1:8081/api/v1/health>.
- Stock et alertes : <http://127.0.0.1:8081/api/v1/snapshot>.
- Sans Anemone : consultation disponible, aucun retrait automatique simulé en remplacement.

## 3. Mobile — Node ≥ 22.11, npm, SDK Android

```powershell
cd code/mobile-app
npm ci
npm start
```

Dans un autre terminal, même dossier :

```powershell
npm run android
```

Dans `code/mobile-app/app.json`, définir `apiBaseUrl` puis relancer l’application :

| Mode | Valeur |
| --- | --- |
| Données simulées locales | `null` |
| Émulateur Android, API du poste hôte | `"http://10.0.2.2:8081"` |
| Téléphone physique | URL HTTPS accessible de l’agent sur le réseau local |

Le transport HTTP de développement dépend de la configuration native Android.
Mode API : actualisation toutes les 5 s, bouton **Actualiser**, erreur visible et **Réessayer**.
iOS : macOS, Xcode et CocoaPods requis ; voir [mobile](../code/mobile-app/README.md).

## 4. Passerelle — Python 3

```powershell
python code/gateway/emitter.py --database "$env:TEMP/smartstock-events.sqlite" --demo
python code/gateway/emitter.py --database "$env:TEMP/smartstock-events.sqlite" --replay
```

Sans ces options : entrée JSONL sur stdin. [Déploiement Pi 3 Model B](../code/gateway/README.md).

## 5. Tests

```powershell
# Racine du dépôt
python -m pip install -r code/connectivity/requirements-test.txt
python -m unittest discover -s code/connectivity -v
python -m unittest discover -s code/gateway -v
python code/anemone-bridge/generate_thermal.py --check
```

Dans `code/smartstock-agent/`, bridge démarré :

```powershell
$env:SMARTSTOCK_ANEMONE_URL='http://127.0.0.1:8092'
sbt scalafmtCheckAll test assembly
```

Dans `code/mobile-app/` :

```powershell
npm run format:check
npm run lint -- --max-warnings 0
npm run typecheck
npm test -- --runInBand
```

Test complet API + Anemone, agent lancé avec `--demo` :

```powershell
python code/tools/integration_smoke.py --url http://127.0.0.1:8081
```

Ce test modifie le jeu de démonstration. Arrêt des services : `Ctrl+C` dans leurs terminaux.
Les capteurs, le BLE et la ventilation physique nécessitent une recette sur matériel.
Les guides officiels dans `doc/` seront rédigés par l’équipe en LaTeX.
