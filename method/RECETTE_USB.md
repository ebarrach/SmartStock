# Recette USB — scanner natif SmartStock

L’application utilise sa caméra DataMatrix, sans QRbot. Sur APK autonome et réseau Wi-Fi, suivre [WIFI_ANDROID.md](WIFI_ANDROID.md). Les producteurs Raspberry/MQTT restent conservés.

## Backend courant et téléphone USB

```powershell
cd D:\Projects\Java\SmartStock
.\code\tools\Start-PhoneServer.ps1 -Java 'C:\Users\esteb\.jdks\ms-21.0.12\bin\java.exe'
# Dans un autre terminal, si le téléphone est en USB :
& 'C:\Users\esteb\AppData\Local\Android\Sdk\platform-tools\adb.exe' reverse tcp:18119 tcp:18119
```

Ce backend utilise les données existantes sous `code/data`, sans réinitialisation. Le mode sans capteurs est un réglage serveur persistant, accessible depuis Connexion. L’APK autonome livré limite HTTP à l’IP LAN exacte : utiliser son URL Wi-Fi ; le localhost USB nécessite la variante debug ou une exception locale explicitement préparée.

## Recette d’exemple séparée

Assembler le JAR (`sbt assembly` dans `code/smartstock-agent`). Initialiser une seule fois, explicitement, un dossier de test distinct :

```powershell
.\code\raspberry\start-usb-recipe.ps1 -InitializeExamples -Java 'C:\Users\esteb\.jdks\ms-21.0.12\bin\java.exe'
# Redémarrages suivants, sans -InitializeExamples :
.\code\raspberry\start-usb-recipe.ps1 -Java 'C:\Users\esteb\.jdks\ms-21.0.12\bin\java.exe'
```

API locale18129, dossier `code/data/recipes/usb`. Ce lanceur refuse le dossier courant `code/data`, n’écrase pas une recette existante et active explicitement les flags initiaux sans balance. Le réglage persistant prévaut ensuite. Pour cette recette isolée, ajouter la redirection18129 et choisir son URL dans l’application debug.

DataMatrix : [usb-datamatrix.svg](../code/raspberry/examples/usb-datamatrix.svg), `(01)05400711223344(17)291231(10)USB(21)USB0001`. La fixture marque explicitement la série absente via un mouvement historique d’exemple. Scan → ajout0→1 ; retry identique → pas de changement ; Scanner à nouveau → retrait1→0. Ne pas importer ce produit dans le stockage courant pour tester.

## Caméra et fiabilité

Autoriser la caméra, cadrer un seul DataMatrix. La première lecture valide verrouille les images suivantes et peut émettre un bip bref, désactivable. Le bip confirme la lecture, jamais un mouvement. Les séparateurs GS1ASCII29 sont conservés. Refus d’autorisation, code invalide, produit inconnu, lot ambigu et erreur réseau sont des états distincts.

Une série inconnue ne bascule pas sur la quantité du lot : choix explicite, restrictions historiques conservées. Produit inconnu : saisir le vrai nom ; lot nouveau : enregistrer un lot vide sans dupliquer le produit ; masse non renseignée=`null`. L’ajout reste une confirmation séparée. Aucun nom, gramme ou état initial n’est inventé.

Après résultat confirmé, Stock/Historique sont actualisés. « Scanner à nouveau » crée une opération volontaire distincte. En résultat réseau incertain, réessayer la même opération : UUID/requête conservés localement, aucun double mouvement. En mode avec capteurs, le scan seul reste en attente d’un poids calibré avec opération/source/lot partagés. Aucun repli simulé en cas de panne.

Pour la variante debug avec Metro8082 : `npm start -- --host 127.0.0.1 --max-workers 2`, redirection8082, puis `npm run android -- --no-packager --active-arch-only` depuis `code/mobile-app`. Pour l’APK autonome, aucun Metro. Ne jamais désinstaller pour contourner une différence de signature sans préserver les données.

Arrêt : Ctrl+C dans le terminal concerné, retirer uniquement ses redirections avec `adb reverse --remove tcp:18119` (et8082/18129 si utilisées). Ne supprimer aucune donnée. Structure JSON, sauvegarde et retour arrière sans perte : [STOCKAGE_JSON.md](STOCKAGE_JSON.md).
