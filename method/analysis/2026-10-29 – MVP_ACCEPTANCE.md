# Critères observables du MVP — SS-001.2

Ce document définit ce qui devra être accepté, sans déclarer les fonctions livrées.
La checklist historique remise à vérifier conserve le périmètre détaillé.
Matériel cible : Raspberry Pi 3 Model B ; DataMatrix remplace NFC.

| Exigence | Scénario et critère de réussite | Preuve attendue |
|---|---|---|
| Identification | Lire un DataMatrix produit/lot connu ; rejeter un code inconnu ou illisible sans modifier le stock | Lecture réelle + test données invalides |
| Retrait principal | Stock compresses 40 ; identifier le lot et mesurer une baisse correspondant à une unité ; confirmer un seul retrait ; stock 39 ; un mouvement historisé | Modèle et trace Anemone, test d'intégration, essai prototype |
| Ajout | Code identifié + augmentation de poids cohérente : incrément exact ; zéro/non-fini/incohérent : aucune confirmation automatique | Tests de corrélation et calibration |
| Lots / conservation | Deux lots d'un produit restent distincts ; quantités jamais négatives ; après redémarrage retrouver stock et historique | Test de stockage/redémarrage |
| Correction manuelle | Correction explicite et historisée avec origine ; erreurs de saisie rejetées | Test service + écran |
| Stock faible / écart | Franchir le seuil configuré : alerte identifiable ; désaccord poids/stock : anomalie, pas correction silencieuse | Tests limites et démo |
| Péremption | Chaque lot contient une date ; tester avant/au-delà des seuils et date passée avec horloge maîtrisée | Tests déterministes et affichage |
| Température / humidité | Mesures valides horodatées, historique ; seuil franchi : alerte ; non-fini/hors domaine : erreur explicite | Tests injectés puis mesures réelles |
| IR / panne | Distinguer présence/absence ; détecter indisponibilité et récupération ; ne pas prétendre mesurer pendant panne | Tests injectés + débranchement réel |
| Ventilation | Représenter au moins arrêt/faible/fort selon températures de démonstration ; commande adaptée au driver réel | Modèle Anemone thermique et mesure actionneur |
| Armoire Wi-Fi | Téléphone et Pi échangent en réseau local sans internet ; données conformes et état hors connexion visible | Intégration HTTPS avec confiance certificat |
| Sac BLE | Lire événements du sac depuis téléphone/Pi ; tester portée, perte/reconnexion et double connexion ou alternance documentée | Firmware et essais GATT |
| Synchronisation | Même id retransmis : un effet ; ack perdu : reprise ; ordre par source ; données conservées pendant coupure | Simulation puis essai réel |
| Mobile | Stock armoire/sac, détail, environnement, alertes et mouvements lisibles ; rafraîchir après opération ; afficher vide/chargement/périmé/hors connexion | Tests composants, parcours et essai téléphone |
| Éclairage / caméra | Identifier lecteur réellement retenu et commande LED ; démontrer leurs fonctions sans inférer leur présence de la checklist | Nomenclature et essai matériel |

## Anemone et démonstrations

La simulation Scala de lot 40 → 39 ne remplace pas le modèle Anemone attendu :
`datamatrix_detecte` + `poids_change` → `retrait_confirme` → mise à jour unique.
L'autre scénario représente les niveaux thermiques et la puissance du ventilateur.
Le Visualizer reçu pendant l'audit fournit ces modèles et un pont vers le JAR
réel. Les sept scripts ont été exécutés avec succès ; modèles/procédures conservés
sous `method/design/anemone`, avec empreintes et méthode de reproduction. Les
sources du pont restent dans le dépôt imbriqué utilisateur, sans import global.
La démonstration a des entrées injectées : aucune lecture physique n'est prouvée.

## Critère de fin du cadrage

Le périmètre et ces méthodes de vérification peuvent être revus sans avoir
implémenté tout le MVP. L'ancienne Definition of Done de SS-001.2 demandait aussi
une représentation Anemone et un lien dans ClickUp : la première est maintenant
prouvée, mais le lien reste à joindre manuellement. Ce dernier empêche un OUI
inconditionnel pour cette sous-tâche.
