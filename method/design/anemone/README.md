# Modèles Anemone conservés pour le cadrage

Copie à l'identique du 29/10/2026 des définitions, agents, politique et procédures
présents dans `method/implementation/SmartStock-Visualizer/`. Les empreintes
`source-sha256.json` relient chaque copie à sa source. Ce dossier versionne les
modèles utiles à la revue S1 ; il ne duplique pas le pont Java et l'interface.

Le Visualizer est arrivé dans le workspace pendant l'audit et possède son propre
dépôt Git imbriqué sans commit. Son index et ses fichiers sont laissés intacts.
Le JAR Anemone reste externe ; il n'est ni modifié ni distribué ici.

Les deux procédures TXT expliquent comment reconstruire les scénarios dans la
GUI Anemone. Les fichiers connected servent au retrait et temperature à la
régulation. Les équations calculent pred(q) et fan(q) dans Anemone, pas dans un
substitut Java/Scala. Le générateur éventuel appartient au Visualizer : ne pas
modifier indépendamment les copies générées température.

## Vérification locale reproduite

Bridge compilé sous Temurin 8u504, serveur de test 127.0.0.1:18092. JAR réel :
`root-assembly-0.1.0-SNAPSHOT.jar`, SHA-256
`CC64E3CF8F3FF6E00CFF4D593FEEE7B02B2AA64A1BAB39824EEE5C9D3226881B`.
Le chemin est configurable par ANEMONE_JAR dans le Visualizer.

Depuis la racine du Visualizer, démarrer `bridge/start.ps1 -Port 18092`, puis
définir SMARTSTOCK_URL=http://127.0.0.1:18092 pour les tests Node :

1. experiments/documentation-tests.mjs
2. experiments/api-tests.mjs
3. experiments/diagnostics-tests.mjs
4. experiments/temperature-api-tests.mjs
5. experiments/data-source-tests.mjs
6. experiments/temperature-demo-tests.mjs
7. experiments/ui-contract-tests.mjs (dernier : arrête le serveur)

Les sept scripts ont terminé avec code 0 le 29/10. Les diagnostics vérifient les
classes réellement chargées depuis le JAR, les définitions et appels run_one.
Retrait observé 40 → 39 puis 38, attente/reprise avec un événement seul, pas à pas,
SSE et déduplication HTTP bornée ; température 15–35 °C, puissances 0/25/50/75/100,
retour à 0 et isolation des deux sessions. Le script UI utilise un double DOM,
pas un navigateur graphique. Logs détaillés dans le carnet local.

Limites : entrées capteurs injectées, stock en mémoire, pas de calibration,
pred(0) absent, pas de corrélation temporelle physique et pas de persistance métier.
Ces preuves ne valident ni le prototype matériel ni l'intégration mobile/Scala.
L'import complet du Visualizer dans le dépôt collectif relève du travail Anemone
SS-013 ; il n'est pas marqué terminé au titre de cet audit S1.
