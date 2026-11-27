# Connexions Maison et hotspot Android

Les profils sont des préférences locales. Ils ne modifient ni stock ni paramètres serveur. Maison reprend l’adresse enregistrée lors de la migration. Ajouter, modifier, tester puis sélectionner un autre profil dans Connexion. Une modification ne change pas silencieusement l’adresse active ; sélectionner explicitement pour l’appliquer. Pour supprimer le profil actif, sélectionner d’abord un autre profil.

Maison : http://192.168.0.143:18119.
Point d’accès constaté : http://10.100.76.76:18119 (adresse du PC WiFi, pas la passerelle Android).

## Serveur

Depuis D:/Projects/Java/SmartStock :

```powershell
./code/tools/Start-PhoneServer.ps1 -InterfaceAlias WiFi -Java C:/Users/esteb/.jdks/ms-21.0.12/bin/java.exe
```

Sur Ethernet Maison, remplacer WiFi par « Ethernet 2 », ou omettre InterfaceAlias pour afficher les interfaces physiques actives. Address permet un choix explicite par IPv4, y compris sur une interface virtuelle. Le choix filtre les URL présentées ; l’écoute reste0.0.0.0:18119. Les interfaces VPN/virtuelles sont exclues par défaut. Chaque URL reste candidate jusqu’au test depuis le téléphone. Aucun changement automatique de profil Windows ou pare-feu.

## Hotspot

1. Activer le point d’accès Android et connecter le PC à son WiFi.
2. Lancer le serveur sur les mêmes JSON code/data.
3. Relever l’IPv4 du PC affichée.
4. Sur le téléphone hôte, ouvrir http://IP_DU_PC:18119/api/v1/health.
5. Dans SmartStock, ajouter « Point d’accès téléphone », saisir cette URL, tester puis sélectionner.
6. Vérifier Stock, Graphiques, Scanner puis fermer/rouvrir.

Le téléphone hôte a effectivement reçu HTTP200 de10.100.76.76 durant cette recette. Le profil Windows est resté Public, sans changement de règle. Si un autre réseau bloque l’accès, vérifier serveur démarré, IP du PC et isolation des clients avant de modifier le pare-feu.

Sur un réseau personnel de confiance uniquement, commandes administrateur à examiner :
```powershell
Get-NetConnectionProfile
Set-NetConnectionProfile -InterfaceAlias WiFi -NetworkCategory Private
Get-NetFirewallRule -Name SmartStock-Local-18119
```
La règle existante est limitée à Java, TCP18119, LocalSubnet, profil Privé. Ne pas désactiver globalement le pare-feu ni ouvrir le routeur. Si Android interdit l’accès hôte/client sur un autre appareil, utiliser un point d’accès tiers ou la recette USB explicite ; ne pas annoncer le hotspot comme validé.

## APK autonome et adresse DHCP

HTTP est limité à192.168.0.143 et10.100.76.76 dans cet APK. Si le hotspot change l’IP du PC, modifier le profil sans supprimer Maison, puis reconstruire l’exception Android avec la liste actualisée. Aucun certificat HTTPS n’est désactivé.

```powershell
# Depuis code/mobile-app/android, JDK21 et SDK configurés :
./gradlew.bat :app:assembleStandalone '-PsmartstockLocalHttpHost=192.168.0.143,10.100.76.76' --no-daemon --max-workers=2
adb install -r app/build/outputs/apk/standalone/app-standalone.apk
```

L’APK embarque JavaScript et ressources ; Metro et adb reverse inutiles à l’utilisation. Ne pas désinstaller pour contourner une différence de signature.

Une opération en attente garde son identifiant et son serveur. Le changement vers une autre adresse est refusé jusqu’à sa résolution dans Scanner. Aucun replay vers un autre profil et aucun repli fictif.
