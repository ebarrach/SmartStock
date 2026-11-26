# Catalogue, historique et prévision

## Fichiers

- `code/smartstock-agent/config/catalog.json` : définitions réelles, initialement vides. Racine `{ "exampleData": false, "entries": [] }`.
- Chaque entrée contient `product` (`id`, `name`, `dataMatrix`, `unitWeightGrams`, `minStock`), `category`, `unit`, `targetQuantity`, `leadDays`, `packSize`, `coverageDays`, `safetyDays`, `storageConditions`. Voir `config/demo-catalog.json` pour le format complet, sans copier ses exemples en mode réel.
- `SMARTSTOCK_DATA_DIR` : dossier des JSON persistants, défaut `code/data/`. Définitions et noms courants dans `products.json` ; aucune réinjection du catalogue au redémarrage. Voir [STOCKAGE_JSON.md](STOCKAGE_JSON.md).
- Pour les essais, choisir un dossier distinct sous `code/data/recipes/`. Le profil et le mode sans capteurs sont deux notions séparées.
- `config/demo-history.json` : scénario déterministe de mai à novembre 2026, marqué `exampleData`, généré avec `node config/generate-demo.cjs`. Chargé uniquement sur demande `--demo --init-demo`, dans un stockage vide. Les médicaments sont fictifs, sans conseil de traitement.
- `code/shared/api/examples/demo-*.json` : exports de l’API Scala pour le mode Démo hors ligne du mobile. Les calculs ne sont pas reproduits dans le client.

## Historique

Une seule source persistante : l’inventaire JSON existant, écrit atomiquement par l’acteur de stockage. Chaque mouvement conserve identifiant, horodatage ISO avec fuseau, produit, lot, delta, quantité après, identifiant de commande, type, origine et motif. Types : `consumption`, `replenishment`, `correction`, `loss`, `expiry`. Un ancien mouvement reste `legacy`, jamais assimilé à une consommation.

`POST /api/v1/movements` accepte `batchId`, `delta`, et les options `kind`, `source`, `reason`. Sans type : correction. Réutiliser le même `X-Command-Id` pour une relance identique ; contenu différent avec le même identifiant refusé. Les événements DataMatrix/poids corrélés produisent une consommation ou un réapprovisionnement selon le signe.

Les 1 000 dernières mesures brutes sont conservées avec des agrégats journaliers UTC par type/source (`count`, `total`, `min`, `max`) dans le même fichier. La lecture d’un ancien fichier ne peut récupérer que les mesures encore présentes ; aucune ancienne mesure n’est inventée. Les dates sans mesure ont des valeurs `null`.

## API et graphiques

- `GET /api/v1/catalog`
- `GET /api/v1/analytics?months=3|6|12` (défaut 6)

Consommation = sorties explicitement typées consommation. Entrées/sorties incluent les autres mouvements, mais corrections et réapprovisionnements ne gonflent jamais la consommation. Les agrégations sont UTC. Un mois sans trace est inconnu, pas nul ; le mois courant est partiel. Un stock historique nécessite les états initiaux de tous les lots et leur cohérence avec les mouvements. Les suggestions affichent la dernière trace, la période analysée et la date de calcul.

## Heuristique Scala/Akka

Au moins deux mois complets observés, des états initiaux cohérents et des paramètres catalogue sont requis. Les mois sans données et le mois courant sont exclus ; une rupture dans la fenêtre déclenche un repli prudent sur seuil minimal/cible.

1. Consommation journalière de chaque mois = consommation / jours du mois.
2. Plafonnement à deux fois la médiane des taux observés pour limiter un mois exceptionnel.
3. Moyenne mobile des trois derniers taux ; comparaison aux trois précédents, tendance limitée à ±20 %.
4. Horizon = délai + couverture + jours de sécurité.
5. Besoin = max(seuil minimal, plafond(taux estimé × horizon + écart-type des taux récents × racine(délai))).
6. Stock utilisable = lots non périmés avant la livraison estimée. Si suffisant : suggestion zéro. Sinon : max(besoin, cible) − stock, arrondi au conditionnement supérieur.

Repli explicite si données insuffisantes : déclenchement sous le seuil, complément vers la cible, arrondi au conditionnement. Aucune commande fournisseur ni écriture de stock automatique.

Limites : demande enregistrée uniquement, ruptures pouvant masquer la demande, mois incomplets exclus, absence de saisonnalité et de pourcentage de confiance. Les commandes confirmées ne sont pas suivies. Les lots périmant pendant toute la couverture ne sont pas modélisés. La continuité entre traces est une hypothèse, pas une preuve de présence permanente des capteurs. La comparaison rétrospective utilise seulement les mois antérieurs au mois évalué, avec la même formule ; résultat : erreur absolue moyenne en unités et nombre de mois évalués.

Les graphiques partagent leur logique Android/web et utilisent SVG via `react-native-svg` sur Android et un adaptateur DOM SVG sur web. Détails au toucher/survol, navigation précédente/suivante et résumé textuel accessible. Voir [DEMARRAGE.md](DEMARRAGE.md) pour lancer et tester.
