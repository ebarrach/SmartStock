# Stockage JSON et migration

## Lancement PC

```powershell
cd D:\Projects\Java\SmartStock
.\code\tools\Start-PhoneServer.ps1 -Java 'C:\Users\esteb\.jdks\ms-21.0.12\bin\java.exe'
```

Garder ce terminal ouvert. Données : `D:\Projects\Java\SmartStock\code\data`. Profil `Demo` conservé pour le stockage issu de la recette ; ce profil n'active ni un remplacement fictif des réponses API ni une réinitialisation. `settings.json` conserve le choix avec/sans capteurs indépendamment du profil. Android autonome : `http://192.168.0.143:18119` sur le LAN actuel, sans Metro.

`-DataDirectory`, `-JarPath`, `-Mode` et `-Port` permettent des profils explicitement distincts. Les chemins relatifs sont résolus depuis la racine du dépôt, pas le terminal appelant. Le lanceur historique du carnet redirige vers celui-ci. Les chemins personnalisés des anciennes variables monolithiques ne servent qu'à la compatibilité/migration et aux tests isolés ; les lanceurs ordinaires les effacent au profit de `SMARTSTOCK_DATA_DIR`.

Le service cherche la racine via `SMARTSTOCK_HOME`, puis les parents du JAR/projet. Raspberry : `SMARTSTOCK_HOME=/opt/smartstock`, `SMARTSTOCK_DATA_DIR=/opt/smartstock/code/data`, JDK21, dossier accessible en écriture au compte `smartstock`. Un ancien `/var/lib/smartstock/inventory.json` doit être sauvegardé puis migré explicitement, pas mélangé au nouveau dossier.

## Structure définitive

Voir le [tableau de code/data/README.md](../code/data/README.md). Les tableaux `products`, `batches`, `movements` et les reçus sont répartis sans changer leurs identifiants ni leur contenu métier. Les paramètres de calibration sont séparés du mode serveur. `metadata.json` conserve le profil et les baselines, `sensors.json` conserve observations et agrégats.

La présence d'une série provient de son dernier mouvement connu : delta positif = présente, négatif = absente. Le lot, la source, le mouvement, l'événement et la provenance initiale sont inscrits dans `units.json`. Ce fichier est recalculé lors de la transaction et vérifié à la lecture contre l'historique ; il n'introduit pas un second moteur métier. Aucune série inconnue n'est déduite de la quantité seule.

`products.json` est la définition persistante courante, y compris les noms saisis par l'utilisateur. Les catalogues versionnés restent des paramètres de prévision et des modèles d'initialisation. **Aucun produit ni nom n'est remplacé au redémarrage.** `--init-catalog` ou `--demo --init-demo` initialise seulement un stockage vide, à la demande explicite ; ne pas utiliser ces options sur les données migrées.

## Garantie transactionnelle

Un seul `InventoryActor` écrit et possède `store.lock` pendant toute la vie du serveur.

1. Écrire et forcer `transaction.json` avec l'état avant/après et l'identité de transaction.
2. Remplacer les neuf JSON, chacun par fichier temporaire forcé puis renommage atomique.
3. Remplacer atomiquement `commit.json` avec l'identité et les empreintes SHA-256. C'est le point de commit.
4. Confirmer l'état au demandeur seulement après cette persistance ; retirer le journal terminé.

Avant le commit, une reprise restaure l'ancien état. Après le commit, elle réapplique le nouvel état. Le journal reste jusqu'à la fin d'une reprise : une seconde interruption est récupérable. Une erreur de restauration interdit les nouvelles écritures jusqu'au redémarrage. Les API ne voient aucun état partiel : la boîte aux lettres de l'acteur sérialise aussi les lectures. Un reçu enregistré évite le double mouvement après replay.

Les JSON ne sont pas des exports manuels : ils sont tous écrits par les transactions et relus au démarrage. Un fichier modifié isolément ou une empreinte incohérente provoque un refus, jamais une remise à zéro. La garantie testée porte sur les erreurs d'E/S et interruptions de processus ; aucun essai de coupure électrique physique n'est revendiqué.

## Migration explicite

Arrêter le serveur, sauvegarder le fichier source et les anciens fichiers cibles, vérifier les empreintes, puis lancer :

```powershell
$java='C:\Users\esteb\.jdks\ms-21.0.12\bin\java.exe'
$jar='D:\Projects\Java\SmartStock\code\smartstock-agent\target\scala-2.13\smartstock-agent.jar'
& $java -cp $jar smartstock.inventory.StorageMigration '<source-absolue.json>' 'D:\Projects\Java\SmartStock\code\data' --allow-examples
```

`--allow-examples` est nécessaire pour le profil d'origine marqué `demo`, pas pour des données réelles. Le programme conserve tous les champs reconnus, refuse les champs inconnus, vérifie l'équivalence complète et ne fusionne aucune ancienne source. Une relance identique est sans effet ; une destination déjà avancée différemment provoque un refus. Ne pas relancer une ancienne migration pour restaurer des données.

Les JSON d'exécution sont ignorés par Git ; les modèles de `config/`, exemples de contrats et README restent versionnés. Les anciens fichiers déjà suivis sont supprimés dans le working tree après sauvegarde ; les nouveaux noms évitent de faire apparaître les données utilisateur comme modifications de fichiers suivis. Aucune opération d'index/commit n'est réalisée automatiquement.

## Retour arrière sans perdre les opérations récentes

Arrêter le serveur. Copier le dossier JSON **complet** vers un nouveau dossier de sauvegarde, puis exporter le dernier état vers un fichier nouveau hors du dossier actif :

```powershell
& $java -cp $jar smartstock.inventory.StorageExport 'D:\Projects\Java\SmartStock\code\data' '<nouveau-chemin-absolu\retour-actuel.json>' --allow-examples
```

L'export prend le verrou, reprend le journal éventuel, vérifie l'équivalence et refuse un fichier de destination existant. Il contient aussi les mouvements postérieurs à la migration.

Pour réutiliser l'ancien JAR sauvegardé, dans un terminal séparé après arrêt du nouveau service :

```powershell
$env:SMARTSTOCK_DEMO_DATA='<chemin-absolu\retour-actuel.json>'
$env:SMARTSTOCK_CATALOG='D:\Projects\Java\SmartStock\code\smartstock-agent\config\demo-catalog.json'
$env:SMARTSTOCK_BIND='0.0.0.0'
$env:SMARTSTOCK_PORT='18119'
Remove-Item Env:SMARTSTOCK_DATA_DIR -ErrorAction SilentlyContinue
& $java -jar '<sauvegarde\smartstock-wifi-final.jar>' --demo
```

La sauvegarde initiale seule convient uniquement si aucune opération ultérieure n'a eu lieu. Ne jamais la recopier par-dessus un stockage plus récent. Les sources et lanceurs ne sont pas restaurés globalement avec Git.

## Vérifications reproductibles

```powershell
cd D:\Projects\Java\SmartStock\code\smartstock-agent
sbt scalafmtCheckAll test assembly
cd ..\..
python code/connectivity/wifi_settings_smoke.py --jar code/smartstock-agent/target/scala-2.13/smartstock-agent.jar --java C:/Users/esteb/.jdks/ms-21.0.12/bin/java.exe
# MQTT : dépendances de test locales requises (paho-mqtt/amqtt), ports distincts.
python code/connectivity/mqtt_smoke.py --jar code/smartstock-agent/target/scala-2.13/smartstock-agent.jar --java C:/Users/esteb/.jdks/ms-21.0.12/bin/java.exe
```

Ces recettes utilisent des stockages temporaires et des événements synthétiques. Les tests de panne n'écrivent jamais dans le stock courant. Si le JAR assemblé est utilisé par le serveur Windows, construire un JAR candidat distinct avant remplacement, ou arrêter ce service.
