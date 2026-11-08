# Vérification et revue SmartStock

## Matrice exigences / tests (SS-006.2)

Les tests de simulation S1 sont conservés ; les lignes du service produit
ci-dessous vérifient la chaîne persistante séparément.

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
| Guides PDF | docs.yml : existence et taille > 0 | Rédaction LaTeX par le groupe ; les emplacements vides ne valident aucun guide |
| Anemone | Sept scripts du Visualizer, JAR réel et diagnostics runtime | Sources locales imbriquées ; pas capteurs réels |
| API / contrat | ApiSpec et test_contract.py contre le service réel | Pas de recette native/radio |
| Stock persistant / corrélation | InventorySpec, CorrelatorSpec, StockInputsSpec | Entrées injectées, calibration physique requise |
| Concurrence | ConcurrentApiSpec : écritures distinctes, doublons, stock insuffisant | Un service de stockage, pas plusieurs processus partageant le fichier |
| Anemone connecté | AnemoneIntegrationSpec et integration_smoke.py : retrait et hystérésis | JAR externe réel requis ; pas d’actionneur physique |
| Mobile API | Tests repository et App : polling, reprise, détail, chargement | Renderer JS, pas ergonomie sur téléphone |
| Qualité globale | Qodana manuel | Secret et run distant non vérifiés |

Critères : zéro échec de test ; zéro erreur compilation/types ; format et lint
sans avertissement ; logs conservés. Vérifier les exceptions runtime même si un
outil retourne 0 : le premier sbt run a révélé un accès au contexte parent depuis
un acteur enfant, corrigé et retesté avec MainSpec et exécution réelle.
