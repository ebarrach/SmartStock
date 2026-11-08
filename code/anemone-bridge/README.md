# Anemone Bridge canonique

Ce module réutilise les sessions Java, le serveur local, les snapshots et le
diagnostic runtime du SmartStock-Visualizer. Le parser `AnimBachSimulParser`,
`ABBlackboard` et `ABSimulExec.run_one` restent dans le **vrai JAR Anemone externe**.
Aucun parser de console ni second moteur n'est introduit.

L'agent Scala2.13/JDK21 parle HTTP avec ce processus Scala2.12/JDK8. Cette frontière
évite de charger deux versions incompatibles de scala-library dans une JVM.
Le Visualizer historique reste intact et peut continuer à fonctionner seul.
Les modèles utiles sont désormais ici sous anemone-scenarios/ et config/.

Sous PowerShell avec JDK8/java/javac dans PATH :

```powershell
$env:ANEMONE_JAR='D:/chemin/vers/root-assembly-0.1.0-SNAPSHOT.jar'
./bridge/start.ps1 -Port 8092
```

Puis configurer `SMARTSTOCK_ANEMONE_URL=http://127.0.0.1:8092` dans le processus
agent pour raccorder les températures à la décision réelle de ventilation.
Le bridge reste sur loopback. `GET /api/diagnostics` expose l'origine réelle des
classes et les compteurs run_one ; la consultation ne fait pas avancer le moteur.

L'adaptateur `AnemoneClient` réutilise le protocole existant : GET state pour le
jeton, POST protégés et identifiants stables de commande, JSON de snapshots.
`AnemoneThermal` sérialise les pas jusqu’à la décision (limite16), lit fanPower décidé par Anemone et
publie une observation fan.changed. Aucune puissance n'est calculée en Scala. Les relectures tardives sont acquittées
sans revenir à une ancienne consigne ; le résultat est mémorisé et sa réception
est conservée dans le registre durable de l’agent.
Plage du modèle actuel : 15–35°C au dixième ; hystérésis de descente0,5°C
configurable dans la politique. Le précédent niveau est transmis au Blackboard ;
les équations fan(q,p) décident de conserver ou changer la puissance.
`python generate_thermal.py` prépare les équations ; `--check` vérifie leur cohérence.
source-sha256.json conserve les empreintes de provenance des anciens modèles,
pas celles des modèles maintenant enrichis. Les empreintes runtime sont dans
metadata.properties et sont vérifiées au démarrage. Pas d'actionneur physique raccordé.

Réutilisation : parser/équations et sessions moteur conservés ; transport HTTP et
diagnostics réutilisés après extraction ; animations, DOM et sources Demo restent
spécifiques au Visualizer. Les originaux NFC sont historiques et ne sont pas
chargés. Les empreintes des modèles sources sont dans source-sha256.json.

Tests réels : définir SMARTSTOCK_ANEMONE_URL puis lancer sbt test dans l'agent.
Sans cette variable, le test d'intégration moteur est explicitement annulé ; les
simulations ne prennent jamais sa place. Les sept scripts du Visualizer restent
des contrôles de non-régression séparés, avec son interface historique.

## Stock automatique et reprise

`StockInputs` conserve les observations avant ack, reconstruit le couple après
redémarrage puis demande le retrait au vrai moteur. Chaque opération porte un
UUID commun ; chaque événement a son propre UUID et sa séquence par source.
Le bridge canonique peut initialiser explicitement son contexte de retrait au
stock persistant courant (seed), puis les équations pred décident chaque retrait.
Le modèle fini couvre0..1000 unités ; au-delà, la demande automatique est refusée,
jamais simulée. Les ajouts passent par la validation métier de stock séparée.
La gateway ne fusionne pas. Le registre de mouvements et la déduplication restent
dans le service de stock, pas dans le bridge en mémoire.

Les sessions stock et température du bridge canonique sont réservées au service.
Utiliser une autre instance pour le Visualizer afin de ne pas perturber leurs
exécutions. `code/tools/integration_smoke.py` vérifie le pipeline HTTP complet
sur un service --demo, avec ordre inversé, reprise/idempotence, ajout et retrait.

Configuration du rapprochement côté agent : SMARTSTOCK_CORRELATION_MS (5000 par
défaut) et SMARTSTOCK_WEIGHT_TOLERANCE_GRAMS (0,5 par défaut). La masse unitaire
vient du catalogue et doit être calibrée avant l’usage physique.
