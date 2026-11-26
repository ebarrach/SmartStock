# Données d'exécution SmartStock

Les JSON de ce dossier sont locaux et ignorés par Git. Aucun modèle rempli de données utilisateur n'est versionné.

| Fichier | Contenu |
|---|---|
| `products.json` | Définitions persistantes et noms, y compris produits enregistrés par scan |
| `batches.json` | Lots, codes physiques, péremptions, quantités et classification de traçabilité |
| `units.json` | Présence connue des séries et mouvement/source qui l'établit ; aucun état inventé |
| `movements.json` | Historique canonique daté des mouvements |
| `settings.json` | Mode sans capteurs, génération des opérations, produits archivés |
| `calibration.json` | Calibration et tare avec source du dispositif ; `null` si absentes |
| `receipts.json` | Reçus d'idempotence et séquences sources |
| `sensors.json` | Mesures, inbox scan/poids et agrégats journaliers |
| `metadata.json` | Version, profil d'origine et états initiaux des stocks |
| `commit.json` | Transaction validée et SHA-256 de chaque JSON |
| `transaction.json` | Journal interne temporaire permettant undo/redo après interruption |
| `store.lock` | Verrou exclusif du processus ; aucune donnée de stock |

Ne pas éditer ces JSON à chaud ni recopier isolément un fichier entre deux versions. Passer par l'API ; pour une sauvegarde cohérente, arrêter le serveur et copier le dossier complet. La reprise du journal précède toute lecture du backend. `units.json` est maintenu et vérifié dans la même transaction que les mouvements.

`config/catalog.json` et `config/demo-catalog.json` sous `code/smartstock-agent/` sont des modèles/paramètres versionnés. Les redémarrages ne les réinjectent pas. L'initialisation éventuelle est explicite et réservée à un stockage vide.

`history_exemple_ancienne_version.json` reste un exemple historique conservé par prudence ; ce n'est pas l'historique actif. Les anciens `stock.json`, `serials.json`, `history.json` ont été archivés, sans fusion de leurs données.

Commandes PC, migration, transaction et retour arrière : [method/STOCKAGE_JSON.md](../../method/STOCKAGE_JSON.md).
