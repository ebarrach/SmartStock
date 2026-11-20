# Parcours et maquettes mobiles — SS-003

## Interface actuelle — 20/11/2026

Le HTML du groupe fournit l’identité bleu nuit/turquoise. La même application
React Native utilise une navigation inférieure sur téléphone et une barre
latérale sur tablette/PC. Composants, champs et cartes partagent `src/ui/theme.ts`.

| Écran | Vérification attendue |
| --- | --- |
| Tableau de bord | Stock armoire, dernières température/humidité/ventilation ; date des mesures ; absence explicite si indisponible |
| Stock | Recherche nom/lot, filtre stock faible, cartes mobiles et colonnes larges ; aucun lot du sac |
| Produit/lot | Quantité et péremption du snapshot courant, DataMatrix ; détail actualisé après polling |
| Mouvements/Alertes | Données du repository ; données historiques du sac filtrées, jamais purgées |
| Connexion | Depuis l’en-tête, URL validée, chargement, erreur, réessai ; aucun repli implicite vers la démo |
| Démonstration | Activation explicite et commandes de chargement/vide ; aucune écriture réelle |

`App.tsx` expose `SmartStockApp`, qui assemble `Navigation`, `InventoryScreens`
et les composants partagés. `useInventory` réutilise `StockRepository` et
`ApiStockRepository`. Les adaptateurs `.web` isolent Lucide, le logo et les
zones de sécurité sans dupliquer les écrans ni utiliser une WebView.
Les lectures et détails existent ; les corrections restent des opérations de
l’API, aucune confirmation de correction fictive n’est affichée dans le mobile.

### Perspective pour le rapport

Le sac et ses communications BLE sont une évolution future, hors parcours
opérationnel actuel. Les lots, mouvements et contrats historiques sont conservés.
La caméra/scanner natif, le BLE, le câblage et la calibration exigent des travaux
et recettes dédiés ; les captures navigateur ne prouvent pas une recette native.

## Historique du socle S1

Le squelette React Native 0.81.5 / React 19.1 / TypeScript vient du template
Community CLI 20.0.2. Android et iOS sont conservés pour permettre une intégration
native BLE ultérieure. Le lockfile npm fixe les dépendances installées.

## Parcours et critères des écrans

| Écran | Parcours et résultat attendu |
|---|---|
| Stock | Choisir Armoire ou Sac, afficher quantité et emplacement sans les confondre ; ouvrir un produit |
| Produit | Nom, quantité, lot, péremption et identification DataMatrix ; retour conserve l'emplacement |
| Mouvements | Exemple de retrait 40 → 39 explicitement fictif ; n'altère pas le stock de démonstration |
| Alertes | Exemple de stock faible avec seuil indiqué ; aucune notification réelle annoncée |
| Connexion | Identifier Wi-Fi armoire et BLE sac ; simuler perte puis reconnexion |

Toutes les pages affichent le mode simulation. La déconnexion ajoute une alerte
persistante « données non actualisées » et empêche le chargement simulé. L'état
vide affiche un message, jamais un stock inventé. L'état chargement est déclenché
et terminé explicitement pour rendre la maquette inspectable sans réseau.

La maquette interactive est `code/mobile-app/App.tsx` : titre, bandeau d'état,
navigation, contenu. Texte et boutons natifs, zone sûre et défilement sont utilisés.
Les actions de simulation seront retirées au branchement des services réels.
Les données sont en lecture seule ; aucun ajout/retrait réel n'est exposé ici.

## Vérification

App.test.tsx vérifie navigation armoire/sac/détail/retour, vide/chargement,
déconnexion/reconnexion et caractère simulé des mouvements/alertes.
Les commandes npm ci, format:check, lint, typecheck et test doivent passer.
Le bundle Metro Android doit pouvoir être généré sans téléphone.

Validation visuelle sur écran, compilation APK et essai tactile restent distincts
des tests JS : SDK/émulateur Android absent de l'environnement d'audit, iOS requiert
macOS/Xcode. Ce document et les tests ne prouvent pas ces validations natives.

Références : [environnement React Native 0.81](https://reactnative.dev/docs/0.81/set-up-your-environment),
[limites des tests JavaScript](https://reactnative.dev/docs/0.81/testing-overview).

Résultat du 29/10 : installation propre npm ci, format, lint zéro avertissement,
TypeScript et 4 tests Jest réussis ; bundle Android Metro généré, code 0.
Le bundle ne constitue pas une compilation native APK.
