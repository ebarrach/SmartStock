# Identité Android et préparation de la distribution

## Ressources

- Source retenue : `method/design/2026-11-10 – Smart Stock Medical Cabinet - Logo1 Without BG.png` (1 254 × 1 254, transparent). Le SVG fourni est une vectorisation à aplats moins fidèle aux dégradés ; les quatre originaux restent intacts.
- Régénération depuis la racine, après `npm ci` dans `code/mobile-app` : `node code/tools/generate-android-branding.cjs --preview`.
- Sorties Android : `code/mobile-app/android/app/src/main/res/`, cinq densités mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi, icônes classiques/ronde, premier plan transparent et fond adaptatif séparé.
- Premier plan : canevas 108 dp, logo dans un carré de 46 dp, diagonale inférieure au cercle sûr de 66 dp. Aperçus cercle et carré arrondi dans `carnet-de-bord/branding/`. Les PNG classiques font 48/72/96/144/192 px.
- SplashScreen AndroidX 1.2.0 : logo sur fond bleu pétrole `#073646`, retour vers AppTheme ; pas d’activité de démarrage distincte ni délai artificiel. Canevas 288 dp, logo 132 dp, diagonale inférieure au cercle sûr de 192 dp. Recette de lancement sur appareil encore nécessaire, notamment Android 11 et Android 12+.
- Image Play : `code/mobile-app/store/google-play-icon.png`, PNG RGBA 32 bits, sRGB, 512 × 512, fond carré plein, sans coins ni ombre extérieure ajoutés. Maximum officiel : 1 024 Ko. Google applique son propre masque et son ombre ; ne pas envoyer l’aperçu masqué à la place de cette image.

## APK local et AAB sont deux configurations distinctes

L’identité `com.smartstockmobile`, les versions et la configuration de signature existantes sont conservées. Le helper ne stocke aucun mot de passe : saisie masquée locale, variables du processus retirées ensuite. Voir [création de clé et installation](DEMARRAGE.md#5-android-autonome-et-wi-fi-local).

Depuis la racine, après création/sélection de la clé hors dépôt :

```powershell
# APK LAN : seulement l’IP privée précisée, à adapter au PC actuel
./code/tools/Build-AndroidRelease.ps1 -Target Apk -Keystore "$env:USERPROFILE/.smartstock-signing/smartstock-release.jks" -Alias smartstock-release -LocalHttpHost 192.168.0.143

# AAB : HTTPS seulement, aucune exception HTTP locale
./code/tools/Build-AndroidRelease.ps1 -Target Bundle -Keystore "$env:USERPROFILE/.smartstock-signing/smartstock-release.jks" -Alias smartstock-release
```

Sorties :

- APK signé : `code/mobile-app/android/app/build/outputs/apk/release/app-release.apk`.
- AAB signé : `code/mobile-app/android/app/build/outputs/bundle/release/app-release.aab`.
- Sans signature disponible, `assembleRelease` produit `app-release-unsigned.apk` ; `bundleRelease` produit un `app-release.aab` **non signé**. Ces artefacts de vérification ne sont pas prêts à distribuer. Le nom du fichier AAB ne prouve pas sa signature.

Pour compiler sans secret, depuis `code/mobile-app/android` :

```powershell
./gradlew.bat :app:assembleRelease '-PsmartstockLocalHttpHost=192.168.0.143' --max-workers=2 --console=plain
# Ensuite, sans la propriété HTTP : ne remplace pas le fichier APK précédent
./gradlew.bat :app:bundleRelease --max-workers=2 --console=plain
```

Un AAB n’est pas un APK installable directement. Il sert à générer les APK adaptés au téléphone ou à un canal Play, après signature appropriée. Aucune publication, inscription Play App Signing ou modification de clés n’est effectuée par ces scripts.

## Accès des testeurs : limite actuelle

L’APK LAN n’autorise HTTP que pour une IP privée exacte, pas pour tous les PC ni tous les réseaux. Le serveur doit tourner, écouter sur l’interface appropriée, et le téléphone partager un réseau accessible sans isolation client. La règle de pare-feu proposée reste limitée au port, au sous-réseau local et au profil Privé ; rien n’est appliqué automatiquement. Pas de redirection du routeur.

L’AAB HTTPS seul ne pourra pas joindre `http://192.168.0.143:18099`. Pour des testeurs distants : fournir une API accessible et stable en HTTPS avec certificat valide, authentification/autorisation et exploitation adaptées avant toute ouverture. Le backend actuel ne doit pas être publié tel quel sur Internet. En attendant, utiliser la Démo hors ligne ou une recette LAN contrôlée avec l’APK local. Aucun tunnel ni service public n’est préparé ici.

Le passage au Raspberry Pi 3 Model B conserve l’API et l’interface : modifier l’adresse et la configuration du serveur. Avec un nom HTTPS stable, pas de reconstruction de l’exception HTTP. Si l’on conserve HTTP sur une autre IP privée, reconstruire l’APK avec cette nouvelle exception.

## Avant distribution

1. Vérifier dans **votre Play Console** si `com.smartstockmobile` existe déjà, son propriétaire et ses certificats de signature/upload. Aucun accès à ce compte n’a permis de le vérifier ici. Ne pas remplacer arbitrairement la clé d’une application existante.
2. Créer/sélectionner la clé localement, sauvegarder les secrets hors dépôt et signer. Comparer les empreintes ; un APK signé avec une nouvelle clé ne remplace pas l’installation debug. Aucun effacement automatique des données.
3. Vérifier `versionCode` (actuellement 1) par rapport à toute version déjà distribuée ; l’incrémenter pour une mise à jour selon l’historique réel.
4. Recetter démarrage sans Metro, icônes/masques, splash Android 11/12+, mode Démo, API, persistance et panne/reprise sur appareil. Les aperçus générés ne constituent pas une capture native réelle.
5. Recontrôler la compatibilité native 16 Ko sur appareil/émulateur adapté et les exigences Play au moment du dépôt. Target SDK actuel : 36. Compléter fiche, captures, déclaration de données et formulaires applicables dans Play Console ; les validations du compte restent humaines.

Sources officielles vérifiées le 20/11/2026 : [icône Play](https://developer.android.com/distribute/google-play/resources/icon-design-specifications), [icônes adaptatives](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive), [SplashScreen](https://developer.android.com/develop/ui/views/launch/splash-screen), [target SDK Play](https://developer.android.com/google/play/requirements/target-sdk), [pages 16 Ko](https://developer.android.com/guide/practices/page-sizes), [bundletool Google](https://github.com/google/bundletool).
