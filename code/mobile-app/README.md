# SmartStock mobile — maquette S1

React Native 0.81.5 / React 19.1 / TypeScript. Node >=22.11, npm et lockfile.
Projet natif initialisé depuis le template Community CLI 20.0.2.

```sh
npm ci
npm run format:check
npm run lint -- --max-warnings 0
npm run typecheck
npm test -- --runInBand
npm start
```

Dans un deuxième terminal : `npm run android` avec SDK Android et émulateur ou
téléphone configuré. iOS nécessite macOS, Xcode et CocoaPods : installer les gems
du Gemfile puis les pods avant `npm run ios`. Les projets natifs sont fournis,
mais aucune compilation APK/iOS ni validation téléphone n'est prouvée par les
tests JavaScript. Voir la documentation officielle de configuration RN 0.81.

Stock armoire/sac, produit, mouvements, alertes et connexion constituent des
maquettes interactives. Données explicitement simulées ; pas de Wi-Fi/BLE ni de
modification de stock réel. Les boutons de simulation exposent les états vide,
chargement et déconnexion pour revue. Parcours/critères :
`method/design/MOBILE_PARCOURS.md` depuis la racine du dépôt.

## API réelle

Renseigner apiBaseUrl dans app.json pour choisir ApiStockRepository au lancement
(null conserve le mock). En émulateur Android, le poste hôte est normalement
10.0.2.2 ; sur téléphone, utiliser l’URL HTTPS du Pi sur le réseau local.
Le même Snapshot est défini dans le contrat partagé ; erreurs et données invalides
ne déclenchent jamais de repli vers le mock. Le mode API actualise toutes les5s
et le bouton Actualiser permet une demande explicite. Le détail sélectionné reste
lié au snapshot courant. Build natif et radios doivent être vérifiés sur appareil.
