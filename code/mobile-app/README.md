# SmartStock — application Android et navigateur

React Native 0.81.5 / React 19.1 / TypeScript. Node >=22.12, npm et lockfile.
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

Tableau de bord, stock armoire, produit, mouvements, alertes et connexion constituent des
écrans partagés avec le navigateur. Le périmètre opérationnel est l’armoire ;
les lots et mouvements du sac sont conservés mais filtrés dans l’interface.
La navigation est inférieure sur téléphone et latérale dès 768 px. Les commandes
de simulation sont accessibles dans Connexion → Démonstration. Parcours/critères :
`method/design/MOBILE_PARCOURS.md` depuis la racine du dépôt.

## API réelle

Renseigner apiBaseUrl dans app.json pour choisir ApiStockRepository au lancement
(null conserve le mock). En émulateur Android, le poste hôte est normalement
10.0.2.2 ; sur téléphone, utiliser l’URL HTTPS du Pi sur le réseau local.
Le même Snapshot est défini dans le contrat partagé ; erreurs et données invalides
ne déclenchent jamais de repli vers le mock. Le mode API actualise toutes les5s
et le bouton Actualiser permet une demande explicite. Le détail sélectionné reste
lié au snapshot courant. Build natif et radios doivent être vérifiés sur appareil.

## Lancement

- API Scala : port **8081**, voir [démarrage](../../method/DEMARRAGE.md).
- Android : `npm start` (Metro **8082**), puis `npm run android` dans un autre terminal.
- PC : `npm run web`, puis <http://127.0.0.1:5173> ; `npm run build:web` compile la version web.
- Connexion → Adresse de l’API : `/agent` sur le serveur web de développement,
  `http://10.0.2.2:8081` sur émulateur Android. Le proxy Vite transmet `/agent`
  vers `http://127.0.0.1:8081` et évite une configuration CORS de développement.
- La connexion choisie dans l’écran s’applique à la session. `app.json` définit
  le repository au prochain démarrage (`apiBaseUrl: null` = démo explicite).
- Un hébergement web de production doit fournir le même proxy HTTPS `/agent`
  ou une API HTTPS autorisant l’origine du site. `vite preview` vérifie le build,
  mais ne remplace pas cette configuration de déploiement.

Lucide utilise `lucide-react-native`/SVG sur Android et `lucide-react` sur web.
Seuls les adaptateurs d’icônes, logo et zones de sécurité diffèrent ; les écrans,
le repository et les règles d’affichage sont communs. Le navigateur n’accède pas
aux fonctions natives caméra/BLE : aucune action non implémentée n’est proposée.
Le stock est consultable ; les corrections restent disponibles via l’API existante.
