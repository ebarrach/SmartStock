# Jalon 1 : périmètre et architecture cadrés

## Source et méthode

Audit du 29/10/2026. Source de planification : `clickup-export.xlsx`, feuilles
Backlog, Organisation, Sprints et Calendrier. Jalon du 18/10/2026, sprint S1
du 07 au 18/10 : SS-001 à SS-006, 18 sous-tâches. Les références SS sont des
identifiants de planification et non des identifiants natifs ClickUp.
Les statuts exportés ne prouvent aucun avancement. Les preuves détaillées et les
résultats de commandes restent dans le carnet local ignoré par Git.

Chaque sous-tâche doit produire un livrable et une vérification. Les parents
exigent en outre la revue nommée dans l'export. Une réalisation technique ne
remplace pas cette revue. Le pourcentage utilise les 18 sous-tâches, jamais la
somme parents + enfants. Les six parents sont comptés séparément.

## Inventaire vérifié avant intervention (SS-001.1)

- `develop` et `origin/develop` : 78320bd après fetch. Les fichiers build.sbt,
  Main.scala, application.conf, package.json, tsconfig.json et openapi.yaml
  étaient tous vides. Les guides PDF utilisateur et programmeur étaient vides.
- README : identité SmartStock et DataMatrix déjà corrigées ; les fonctionnalités
  annoncées et commandes de lancement ne correspondaient pas à un logiciel livré.
- Trois documents de connectivité présents : architecture, enveloppe et file.
  Plusieurs choix restaient proposés ; le script de simulation cité était absent.
- Documents datés : MVP, exigences Scala, matériel, stratégie de tests. Charter,
  Meeting et Step Charter sont des fichiers modèles vides, sans décisions à inférer.
- Les deux Gantt sont des modèles 2025 sans tâches SmartStock exploitables.
- Trois workflows manuels présents ; aucune réussite distante démontrée.
- Aucun modèle, projet ou trace d'exécution Anemone trouvé dans les arbres courants
  des branches pertinentes ou dans l'historique recherché par nom. La checklist
  affirmait néanmoins une exécution réussie : preuve absente.
- Changements utilisateur préexistants : schéma PDF Wi-Fi/BLE indexé,
  structure.txt modifié, export XLSX non suivi. Ils ne sont pas inclus dans les
  commits de cet audit.

## Branches et décisions d'intégration (SS-002.1)

`origin/feature/scala-akka` contient deux commits absents de develop :
82ffda6 (13/10, Ayoub) et 911d82e (16/10, humidité). Elle contient 15 sources Scala
et 2 suites de tests dans `code/SmartStock`, avec Scala 2.13.16 et Akka 2.6.20.
Compilation et 12 tests vérifiés dans une copie temporaire sous JDK 21, avec sbt
1.10.7. Le `project/build.properties` distant indique sbt 2.0.10 ; cette version
n'est pas celle de cette vérification et n'est pas reprise.

Reprise sélective dans `code/smartstock-agent` : MedicationBatch, BatchActor,
TemperatureActor, HumidityActor, InfraredActor, BatchActorSpec et
TemperatureActorSpec. Les acteurs de stock/capteurs repris sont des simulateurs
en mémoire, sans preuve de matériel connecté ni de persistance.

SmartStockManager, HardwareActor, StockActor, HistoryActor, CatalogActor et
FailureActor restent disponibles sur leur branche. Leur reprise entière est
écartée pour S1 : opérations manuelles et GetStock consultent des acteurs
différents ; historiques de lots et produits séparés ; attentes sans timeout ;
scans concurrents parfois ignorés. Le routeur S1 ne dépend pas de ces chemins.
Ce choix préserve le travail d'Ayoub sans le déclarer intégré ou corrigé.

`origin/anitatelle-patch-1` n'apporte aucun commit absent de develop. Inventaire et
stratégie de tests sont déjà intégrés ; les différences de chemins résultent de
réorganisations ultérieures. Aucun merge nécessaire. Aucune autre branche
distante de travail trouvée après fetch ; main n'est pas utilisée comme référence.

## Décisions actuelles et chronologie

1. 10–19/10 : checklist et analyses historiques, Pi 4/NFC encore dans certains PDF.
2. 22/10 : déplacement des PDF vers method/analysis, sans nouvelle validation physique.
3. 29/10 : consigne utilisateur actuelle : **Raspberry Pi 3 Model B et DataMatrix**.
   Elle prévaut sur les PDF et l'export. Les PDF originaux restent historiques ;
   le correctif matériel daté accompagne toute lecture de ces documents.
4. L'export préparé à partir d'informations du 06/10 annonce une évaluation
   ambiante/mobile le 07/12 et un oral Scala théorique le 22/01. La consigne récente
   annonce une défense en janvier 2027. Date précise et distinction entre cours à
   confirmer sur une source académique actuelle. Le PDF `2627labo (1).pdf` cité
   par l'export est absent. Aucune réservation ni confirmation enseignant déduite.

Jalons internes conservés : checkpoint 02/11 ; BLE 16–19/11 ; intégration
20–22/11 ; checkpoint 23/11 ; candidate/rapport 02/12 ; slides 03/12 ; répétitions
03–05/12. Ce sont des objectifs, pas des réalisations.

## Livrables et frontières S1

| Lot | Livrable vérifiable | Revue parent attendue |
|---|---|---|
| SS-001 | Inventaire ici, critères MVP, exigences Scala et chronologie | Jonathan |
| SS-002 | Domaine immuable, routeur typé compilable, cycle de vie et tests | Pablo |
| SS-003 | Squelette RN/TS, parcours, maquettes interactives et tests | Samuel |
| SS-004 | Architecture gateway, enveloppe, simulation file/reconnexion | Ayoub |
| SS-005 | Nomenclature corrigée, contraintes et montage conditionnel | Anatelle |
| SS-006 | Audit CI, matrice exigences/tests et suivi d'anomalies | Esteban |

S1 initialise les socles exigés dans les descriptions des parents, même si leurs
titres parlent de conception. SS-007 à SS-012 concernent le socle fonctionnel
suivant : pas d'API métier complète ni d'intégration BLE réelle ajoutée en S1.
Les démonstrations matérielles, preuves Anemone, revues humaines et confirmation
des échéances ne peuvent pas être inventées pour clôturer le jalon.

## Complément reçu pendant l'audit : Anemone

À 11:26, `method/implementation/SmartStock-Visualizer` est apparu dans le workspace.
Le constat initial d'absence reste valable pour l'arbre Git de develop mais plus
pour les ressources locales. Ce dossier contient un dépôt Git imbriqué sans
commit ; aucun changement ni commit n'y est effectué par cet audit.

Les sources ont été examinées et le vrai JAR externe est disponible. Compilation
Java 8 et sept scripts de vérification passent : stock 40 → 39 → 38, événements
isolés, reprise AST, diagnostic runtime, températures/niveaux ventilateur,
indépendance des modes Demo, contrats UI et arrêt propre. Voir
`method/design/anemone/README.md` pour reproduction, empreinte JAR et limites.
Les modèles et procédures utiles sont conservés par copie identique versionnée,
sans importer le dépôt imbriqué comme gitlink ni modifier ses fichiers.
Le lien de livraison dans ClickUp et la revue humaine ne sont toujours pas prouvés.
