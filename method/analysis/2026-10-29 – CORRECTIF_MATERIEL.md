# Nomenclature et montage actuels — SS-005

Ce correctif accompagne les PDF historiques Mounting Specification et Power &
Compatibility Analysis du 19/10. Leurs mentions « VERIFIED », « 100% AVAILABLE »
et « NO EXTRA PURCHASE » ne constituent pas des mesures ou un inventaire vérifié.
Décision utilisateur du 29/10 : **Raspberry Pi 3 Model B, identification DataMatrix**.

## Nomenclature et inconnues

| Élément | État de connaissance | Vérification manquante |
|---|---|---|
| Pi 3 Model B, SD, boîtier, alimentation | Modèle cible confirmé par l'utilisateur | Inventaire physique, démarrage, OS et image 64 bits/JDK 21 compatibles |
| Arduino « 1010 » | Désignation du document d'inventaire | Photo/référence exacte ; ne pas déduire tensions et BLE du seul nom |
| InterfaceKit 8/8/8 et VINT Hub | Déclarés disponibles le 19/10 | Références, câbles, SDK, connectique de chaque capteur |
| Poids / force | Force sensor inventorié ; poids prévu au MVP | Cellules, convertisseur, gamme, calibration et implantation |
| Température, humidité, IR | Inventoriés dans le document du 19/10 | Références, interfaces et mesures de contrôle |
| Caméra ou lecteur DataMatrix | Nécessaire au choix actuel, absent de la liste précise | Disponibilité et essai de lecture de vrais codes |
| Ventilateur et commande | Ventilation prévue ; dual relay board inventoriée | Ventilateur, alimentation, courant, driver et capacité de modulation |
| Sac / batterie / BLE | Concept documenté | Contrôleur exact, autonomie, charge et capacité à deux connexions |
| LEDs, interrupteurs, accessoires | Quantités déclarées dans l'inventaire historique | Identification, résistances et allocation physique |

Le document d'inventaire original conserve ses quantités historiques. La ligne
Pi 4 y est désormais explicitement historique ; aucune disponibilité du Pi 3 ou
du lecteur DataMatrix n'est déduite de cette liste.

## Alimentation et compatibilité (SS-005.2)

Le Pi 3 Model B utilise une alimentation **micro-USB**, pas USB-C. Le fabricant
annonce une alimentation micro-USB jusqu'à 2,5 A et des radios Wi-Fi/BLE intégrées :
[fiche Raspberry Pi](https://www.raspberrypi.com/products/raspberry-pi-3-model-b/).
Un hub USB alimenté peut fournir les périphériques ; il ne prouve ni isolation
galvanique, ni absence de retour d'alimentation, ni budget de courant suffisant.

Ne pas généraliser « tout capteur Phidget est VINT » : associer chaque référence
à l'InterfaceKit ou au VINT Hub compatible. Les tensions des signaux Arduino,
du relais et des périphériques restent conditionnées à leurs fiches exactes.
Le moteur du ventilateur ne se branche pas directement à un GPIO. La compatibilité
3,3 V d'un relais et son isolation ne sont pas garanties par son nom commercial.
Les masses, protections, courant de démarrage, résistances LED et alimentations
doivent figurer sur le schéma final après identification des pièces.

Un relais on/off ne démontre pas plusieurs puissances de ventilation. La démo
thermique doit prévoir arrêt/faible/fort avec un actionneur et driver adaptés ;
les seuils et puissances seront des paramètres d'essai, pas des prescriptions de
conservation des médicaments. Aucun câblage dangereux ni achat n'est réalisé ici.

## Montage minimal et allocation (SS-005.3)

Armoire : Pi gateway sur support ventilé ; cartes d'acquisition accessibles ;
poids sous plateau avec arrêt mécanique ; caméra/lecteur DataMatrix près de la
zone de passage ; IR à l'entrée ; température/humidité loin du flux direct du
ventilateur ; extraction séparée de l'alimentation et câbles fixés. Dimensions
50×50×60 du PDF restent une proposition non mesurée.

Sac : compartiments amovibles, contrôleur BLE et batterie protégés, câblage fixé,
lecture DataMatrix à définir selon lecteur réellement disponible. Les dimensions
45×30×25 et l'étanchéité du PDF ne sont pas vérifiées.

Allocation de conception pour satisfaire les contraintes de l'export :
température/humidité sur interfaces Phidget compatibles ; poids et IR via Arduino
après vérification des interfaces ; Pi assure la passerelle. Quatre fonctions
capteurs, zéro directement sur GPIO du Pi : 0/4, donc au plus 1/3. Cette allocation
est un objectif de câblage, pas une constatation matérielle ; une caméra sur le Pi
doit être comptée dans le bilan final si elle est retenue. Phidget et Arduino
restent deux cartes distinctes. Recalculer le ratio sur le montage réel.

Critères de validation physique : inventorier références/photos, mesurer les
rails et courants sous charge, tester chaque capteur, lire un DataMatrix réel,
vérifier la fixation et le chemin de ventilation. Sans matériel ni mesures,
les tâches de vérification matérielle restent partielles ou bloquées.
