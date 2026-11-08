# Passerelle Raspberry Pi 3 Model B

Préparer Raspberry Pi OS 64 bits compatible avec le Pi3, Python3 et un JDK21 ARM64
vérifié sur la machine. Construire l'agent sur le poste de développement avec
`sbt assembly`, puis transférer le JAR ; ne pas exécuter sbt sur le Pi1Go.
Le bridge Anemone conserve son JDK8 séparé et le JAR externe de l'équipe.

L'unité systemd fournie suppose `/opt/smartstock`, `/opt/java21/bin/java` et un
utilisateur smartstock. Adapter ces chemins, installer explicitement l'unité et
vérifier le compte avant activation. Aucun déploiement ou service n'est installé
automatiquement par les scripts de développement. Commandes sur le Pi :
`systemctl enable --now smartstock-agent`, `systemctl status smartstock-agent`,
`journalctl -u smartstock-agent -f`. Restart=on-failure ; données sous
/var/lib/smartstock, logs journald. Démarrage réel au boot : à tester physiquement.

L'API écoute en loopback8081. Pour le téléphone Wi-Fi, utiliser une terminaison
HTTPS de confiance sur le réseau local ; ne pas ouvrir le port de développement
en dehors du réseau d'essai. Configuration par /etc/smartstock.env, sans secrets
versionnés. Le SDK/OS exact, le réseau, le certificat et les radios seront vérifiés
sur le Pi réel ; cela n'empêche pas les tests de protocole locaux.

Émettre des observations **simulées** et rejouer l'outbox :

```sh
python code/gateway/emitter.py --database /tmp/events.sqlite --demo
python code/gateway/emitter.py --database /tmp/events.sqlite --replay
```

Sans --demo/--replay, lire sur stdin des JSON par ligne :
`{"type":"temperature.measured","data":{"value":20}}`.
La gateway alloue UUID et séquence durable par source ; elle ne fusionne pas les
capteurs et ne calcule pas le stock. Les identifiants sont conservés lors des
retries, les événements non acquittés restent dans SQLite. Les drivers réels
alimenteront cette même frontière ; références matérielles et calibration restent
à fournir. `python -m unittest discover -s code/gateway -v` vérifie le redémarrage
du compteur et la saturation sans perte. Les 14 tests connectivity restent requis.
