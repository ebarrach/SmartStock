# Tests, revue et anomalies — SS-006

Complément à la stratégie du 19/10 ; celle-ci décrit l'état historique et reste
la référence des contrôles Scala et mobile. Les statuts ClickUp ne sont pas une
source de résultats de tests.

## Audit des workflows et tests (SS-006.1)

Avant intervention : trois workflows manuels, sans déclencheurs push/PR.
ci.yml appelait scalafmt sans plugin et npm ci sans manifeste valide/lockfile.
Les deux PDF des guides étaient vides ; docs.yml aurait toléré leur absence et
la simple présence de fichiers vides. Qodana était manuel, sans qualité mesurée.
Les seules suites de code étaient sur feature/scala-akka, absentes de develop.

Après intervention : ci.yml s'exécute sur push develop et PR vers develop/main,
avec trois jobs Scala, mobile et simulation Python. docs.yml reste manuel et
rejette les PDF vides/absents avant archivage. Il ne génère toujours pas les guides.
Qodana reste manuel, dépend du secret QODANA_TOKEN ; aucune analyse distante n'est
annoncée comme exécutée. Les nouveaux workflows ne prouvent pas un run GitHub.

## Matrice exigences / tests (SS-006.2)

| Exigence | Vérification reproductible | Limite |
|---|---|---|
| Domaine / messages / acteur | sbt compile et EventRouterSpec | Ports simulés, pas API HTTP |
| Routage par catégorie | Destinations et enveloppe inchangée testées | Pas de transport réel |
| Erreurs / latence | Non-finis, métadonnées invalides, timeout, mauvais ack, réponses concurrentes | Pas de panne radio |
| Cycle de vie | MainSpec et sbt run : transmission puis arrêt | Pas service systemd |
| Lots / retrait | BatchActorSpec et SensorAndWithdrawalSpec : 40 → 39, historique, rejet stock insuffisant | Simulation explicite, pas corrélation ni Anemone |
| Capteurs | TemperatureActorSpec, humidité/IR dans SensorAndWithdrawalSpec | Valeurs injectées, pas mesures physiques |
| Scala académique | Tableau SCALA_ACTORS.md et tests | Évaluation officielle non confirmée |
| Mobile | npm ci ; format:check ; lint ; typecheck ; Jest | Tests JS, pas APK/téléphone |
| Maquettes / parcours | App.test.tsx : stock, détail, navigation, vide, chargement, offline | Revue visuelle native à effectuer |
| Conteneur événement | test_schema.py : Draft202012 + exemples + rejets | Schémas métiers SS-007 à venir |
| Reconnexion / persistance | test_queue.py : outbox + déduplication durable, ack perdu, redémarrage | SQLite local, pas coupure électrique réelle |
| Matériel | Nomenclature/correctif et procédure de mesures | Références et matériel absents |
| Guides PDF | docs.yml : existence et taille > 0 | Guides encore vides, archivage bloqué volontairement |
| Anemone | Sept scripts du Visualizer, JAR réel et diagnostics runtime | Sources locales imbriquées ; pas capteurs réels |
| Qualité globale | Qodana manuel | Secret et run distant non vérifiés |

Critères : zéro échec de test ; zéro erreur compilation/types ; format et lint
sans avertissement ; logs conservés. Vérifier les exceptions runtime même si un
outil retourne 0 : le premier sbt run a révélé un accès au contexte parent depuis
un acteur enfant, corrigé et retesté avec MainSpec et exécution réelle.

## Revue et suivi des anomalies (SS-006.3)

Les commits directs sur develop sont autorisés par l'utilisateur. main reste
réservée à la revue humaine ; aucun merge automatique. La revue des parents S1
est confiée au vérificateur de l'export, distinct de l'assigné. La CI ne constitue
ni une approbation humaine ni la preuve d'un déploiement matériel.

Chaque anomalie comporte : identifiant local, composant, gravité, attendu,
observé, reproduction, preuve, décision, état et test de non-régression lorsque
pertinent. Le tableau ci-dessous constitue le registre partagé initial. Les
fiches détaillées et logs sont dans le carnet local. Aucun ticket externe n'est
créé automatiquement ; un membre peut reporter la fiche vers GitHub/ClickUp.

| ID | Gravité | Constat / reproduction | Décision et état |
|---|---|---|---|
| A01 | Bloquant | npm test dans mobile : manifeste vide | Squelette, scripts et lockfile ajoutés ; contrôles à consigner |
| A02 | Bloquant | Sources/build Scala vides sur develop | Reprise sélective et routeur ; compilation/tests locaux |
| A03 | Majeur | openapi.yaml vide | Hors S1, SS-007 ; ne pas annoncer API exécutée |
| A04 | Majeur | Checklist prétend Anemone exécuté, sources introuvables | Modèles reçus pendant audit ; sept scripts réels passent, copies versionnées |
| A05 | Majeur | PDF Pi 4/NFC et disponibilité totale non prouvée | Correctif daté Pi 3/DataMatrix, vérification physique bloquée |
| A06 | Majeur | Script queue cité absent ; suppression des plus anciens | Simulation ajoutée ; saturation refuse l'entrée |
| A07 | Majeur | Stock/historique divergents du manager distant | Pas de reprise globale ; décision documentée |
| A08 | Mineur | Guides PDF de taille zéro | Packaging doit échouer ; rédaction prévue ultérieurement |
| A09 | Majeur | Date 07/12 export vs janvier consigne récente | Janvier retenu, confirmation académique requise |
| A10 | Majeur | Contexte guardian utilisé dans le sink enfant au premier run | Contexte propre à l'enfant ; test MainSpec ajouté |

Clôture : relier correction, commande et résultat ; garder le constat initial.
Si matériel, secret, revue ou source académique manque, indiquer ce besoin précis
et ne jamais marquer Terminé sur une simple affirmation documentaire.

## Résultats locaux du 29/10/2026

- Scala : compilation, format scalafmt, 22 tests et exécution du Main réussis.
- Connectivité : 14 tests réussis (11 simulation, 3 JSON Schema).
- Mobile : npm ci réussi (853 paquets), lint sans avertissement, TypeScript et
  4 tests Jest réussis. Format Prettier corrigé et vérifié ; bundle Metro Android généré avec succès.
- Anemone : compilation Java 8 et 7 scripts de vérification réussis avec JAR réel ;
  serveur de test arrêté, code 0. Voir method/design/anemone/README.md.
- Les trois workflows YAML sont analysables ; aucun run GitHub ou Qodana attesté.
- Les 8 copies Anemone et leurs blobs Git correspondent aux empreintes sources.

Les essais initiaux en échec sont conservés dans le journal local et ne sont pas
présentés comme des réussites. Les limites physiques et de revue restent actives.
