# Passerelle et protocole S1 — SS-004

## Architecture et configuration

Pi **3 Model B** : gateway armoire, Wi-Fi local vers application React Native et
central BLE vers le sac. Sac : périphérique/serveur GATT ; téléphone : autre
central. Le contrôleur exact du sac et sa capacité multi-central restent à
vérifier ; l'alternance est le scénario de secours explicitement annoncé.

Configuration à renseigner au déploiement : identifiant unique de chaque source,
adresse/nom du Pi sur le réseau local, certificat HTTPS de confiance sur le
téléphone, ports, UUID du service et des caractéristiques BLE, MTU/fragmentation,
permissions et appairage. Aucun secret, UUID ou certificat fictif n'est livré
comme configuration fonctionnelle. L'OS/JDK et les cartes seront testés sur Pi.
Les trois documents Connectivity restent le détail de conception historique.

## Contrat du conteneur v1 (SS-004.2)

Source machine : `code/shared/events/envelope.schema.json` et exemples JSON.
Huit champs requis, pas de champs supplémentaires :

- `v` : entier 1 ; une autre version est rejetée ;
- `id` : UUID v4 généré une seule fois, conservé à la retransmission ;
- `type` et `src` : chaînes non vides ; type d'événement et source ;
- `seq` : entier >= 0 croissant par source, allocation durable à implémenter ;
- `ts` : ISO 8601 UTC, information temporelle ; l'ordre utilise seq ;
- `corr` : UUID de l'événement acquitté, null pour une observation autonome ;
- `data` : objet JSON ; contenu métier et catalogue de types relèvent de SS-007.

L'ack est lui-même une enveloppe, `type=ack`, `corr=id original`,
`data.status=ok|duplicate|error`. Un ack n'attend jamais d'ack : éviter la boucle.
`ok` et `duplicate` permettent la suppression de l'outbox ; `error`, ack absent
ou corr incorrect la conservent. Un ack n'affirme pas un geste physique.
La simulation utilise seulement la projection interne `{corr,status}` dans son
callback : aucun codec radio ou client HTTPS n'est revendiqué.

## Reconnexion et stockage (SS-004.3)

SQLite persiste l'outbox et le journal de réception de la simulation. L'identité
est unique par id et par (src,seq) ; un doublon identique est acquitté sans nouvel
effet ; une identité réutilisée avec un autre contenu est rejetée. Les opérations
sont transactionnelles. `sending` redevient `pending` au redémarrage.

Ordre par source ; pas d'ordre global entre deux sources. Le premier échec bloque
la progression de la file de simulation ; retries 1,2,4,8,16,32,60,60 secondes,
puis plafond 60. L'horloge est injectée dans les tests, sans attente réelle.
Capacité de simulation : 100 ; file pleine = erreur de backpressure, aucune
suppression d'événement non acquitté. La rétention du journal reçu et un replay
vers plusieurs consommateurs restent à dimensionner : ne pas effacer ses données
sur la base de l'ack d'un seul téléphone sans contrat multi-client.

Au redémarrage du destinataire, la déduplication persiste dans un journal distinct.
Cette propriété est démontrée localement, pas garantie après corruption physique
d'une SD. Le handshake « dernier seq reçu », la persistance du compteur producteur,
le buffer du sac, TLS et la fragmentation BLE relèvent de l'intégration suivante.
Des simulateurs ne prouvent ni la radio ni un redémarrage électrique réel.

## Vérification reproductible

`python -m unittest discover -s code/connectivity -v` : scénarios dans
test_queue.py. Les exemples JSON sont validés par le validateur de frontière
Python ; test_schema.py vérifie aussi le schéma Draft202012 avec contrôle des
formats et rejet de métadonnées invalides. Installer requirements-test.txt avant
la suite. Le même contrôle est configuré en CI.
Essais physiques : démarrage Pi, lecture de chaque carte, HTTPS local sans internet,
BLE de chaque central, coupures Wi-Fi/BLE/électriques, reconnexion et ordre.
