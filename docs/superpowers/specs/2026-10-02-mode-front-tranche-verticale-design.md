# Mode Front : tranche verticale

Date : 2026-10-02
Branche : `feat/front-mode`, issue de `merge/upstream-4.22.1`
Statut : spécification soumise à relecture, aucun code écrit

## 1. Intention

Remplacer le combat terrestre unité contre unité par une résolution de **pression territoriale**, dans l'esprit d'OpenFront mais au tour par tour. Une **division** est un jeton posé sur la carte. Il couvre une **zone d'influence**, y exerce une pression chaque tour et fait changer de main les cases une à une. Le joueur choisit une **posture** par division. Les villes ne sont plus des places fortes : elles sont des centres économiques.

Décisions déjà arrêtées avec l'utilisateur :

- plus de garnison, plus d'unités terrestres individuelles ;
- un jeton par division, qui se déplace avec sa zone ;
- la tranche verticale vient avant tout le reste.

## 2. Critère de réussite de la tranche

La tranche répond à une seule question : **ce mode est-il amusant et lisible à jouer ?** Elle est réussie si, sur un scénario à deux civilisations :

1. un joueur comprend en moins de 5 tours ce que fait chaque posture et ce que lui coûte une erreur ;
2. une partie de 150 tours se termine par au moins une ville prise, sans blocage prolongé ;
3. les quatre postures sont chacune la meilleure dans au moins une situation (aucune posture dominante, aucune inutile) ;
4. deux exécutions avec la même graine donnent exactement le même résultat.

## 3. Périmètre

Dans la tranche :

- division, zone, contact, quatre postures, effectifs, renforts, retrait automatique ;
- changement de propriétaire des cases, chute des villes par le siège existant ;
- interface minimale (choix de posture, affichage de la zone et de la pression) ;
- IA rudimentaire ;
- scénario de test et mesures.

Hors tranche (conservés tels quels, donc à ne pas utiliser dans le scénario) : marine, aviation, nucléaire, débarquement, unités civiles et d'exploration, religion, espionnage.

## 4. Modèle

### 4.1 Division

Une division est une `MapUnit` du type `Division` (un seul type dans la tranche). On réutilise les champs existants :

| Notion | Champ existant | Remarque |
|---|---|---|
| Effectifs | `health` (0 à 100) | Se lit en pourcentage des effectifs maximaux |
| Position | `currentTile` | Déplacement, sélection et rendu inchangés |
| Posture | nouveau champ `stance` | Sérialisé, défaut : défensive solide |
| Retranchement | nouveau champ `entrenchment` | Entier, remis à zéro quand la division bouge |

La **puissance de base** d'une division est une fonction de l'ère de sa civilisation (`baseStrength = 20 × (1 + 0,25 × numéro d'ère)`), pour que le progrès technique compte sans multiplier les types d'unités.

### 4.2 Zone et contact

- La **zone** d'une division est l'ensemble des cases à distance hexagonale au plus **2** de son jeton.
- Une case est **en contact** pour un camp A si elle appartient à un camp en guerre avec A, qu'elle est dans la zone d'une division de A et qu'elle touche une case de A ou occupée par A.
- Chaque division presse au plus **W cases** de contact par tour (largeur de front : `W = 3 + numéro d'ère / 2`, arrondi bas). S'il y en a davantage, elle presse celles les plus proches de son jeton. Déplacer le jeton revient donc à **diriger l'effort**.
- Sa pression se répartit également entre les cases pressées.

### 4.3 Résolution d'une case disputée

Elle a lieu une fois par tour, **après** que tous les camps ont joué, de façon simultanée.

**Pression de l'attaquant** sur la case t, sommée sur ses divisions qui la pressent :

```
P = somme de ( santé/100 × puissanceDeBase × coefficientOffensif(posture) × ravitaillement ) / nombreDeCasesPressées
```

**Résistance du défenseur** sur t, sommée sur ses divisions dont la zone couvre t, plus une résistance de base si aucune ne la couvre :

```
D = somme de ( santé/100 × puissanceDeBase × coefficientDéfensif(posture) × (1 + retranchement) )
    × (1 + bonusDéfensifDeLaCase) × (1 + sympathieCulturelle)
D = résistanceDeBase si aucune division ne couvre la case
```

Le bonus défensif de la case reprend l'existant (terrain, aménagement : +10 % de base du fork). La sympathie culturelle reprend l'existant (±30 %). La résistance de base vaut `10 × (1 + bonus)`, ce qui représente une milice : un territoire dégarni tombe, lentement.

**Progression** de la case, en pourcentage du seuil de changement de propriétaire :

```
ratio = P / D
gain par tour = max(0, ratio - 0,7) × 25
progression += gain ; elle diminue de 20 % par tour sans pression ; la case change de main à 100
```

Ordres de grandeur voulus : ratio 1,7 donne un gain de 25 par tour, soit 4 tours par case. Ratio 1,0 donne 7,5 par tour, soit 14 tours. Ratio inférieur à 0,7 ne progresse pas.

**Pertes** de chaque camp sur la case, réparties entre ses divisions au prorata de leur part :

```
perteDuCampX = 4 × 2 × (forceAdverse / (P + D)) × coefficientSubi(posture de X) × coefficientInflige(posture adverse)
```

À forces égales et postures modérées, chaque camp perd 4 points de santé par case disputée et par tour.

### 4.4 Les quatre postures

Valeurs de départ, toutes à calibrer.

| Posture | Offensif | Défensif | Subi | Infligé | Particularité |
|---|---|---|---|---|---|
| Offensive modérée | 1,0 | 0,8 | 1,0 | 1,0 | Avance régulièrement |
| Offensive agressive | 1,5 | 0,6 | 1,5 | 1,3 | Avance vite, s'épuise, coûte 50 % de renfort en plus |
| Défensive solide | 0 | 1,4 | 0,8 | 1,0 | Retranchement : +5 % par tour immobile, plafonné à +35 % |
| Repli stratégique | 0 | 0,6 | 0,4 | 0,9 | Le jeton recule de 1 case par tour vers la case alliée la plus proche du ravitaillement |

Le repli échange de l'espace contre des effectifs. Les cases que la zone ne couvre plus retombent à la résistance de base.

### 4.5 Changement de propriétaire et chute des villes

- Une case qui atteint 100 passe à la ville du vainqueur la plus proche, par `expansion.takeOwnership(tile)`. La règle anti-enclave du fork s'applique : la case doit toucher le territoire du vainqueur.
- Une case de centre-ville ne change pas de propriétaire directement. Une ville tombe par la **règle de siège existante** (`processSiege`) : tous les voisins du centre sont contrôlés par l'ennemi pendant 3 tours, elle devient alors un satellite du vainqueur. Cette règle lit déjà la propriété des cases, il n'y a rien à changer pour la tranche.

### 4.6 Effectifs, renforts, dissolution

- **Recrutement** : une division s'achète à l'or dans une ville, jamais par la production. Le coût dépend de l'ère.
- **Renforts** : +10 de santé par tour si la division est en territoire allié relié au ravitaillement (même mesure que le malus de ravitaillement existant, distance effective au plus 4) et hors contact ; +3 en posture défensive au contact. Chaque point coûte de l'or.
- **Dissolution** : une division à 10 de santé ou moins est dissoute.

## 5. Ancrages dans le code

Vérifiés sur la branche :

- `CityExpansionManager.takeOwnership` / `relinquishOwnership` : changement de propriétaire d'une case et conséquences (population, statistiques, historique).
- `CityTurnManager.processSiege` : chute des villes par encerclement, lit déjà la propriété des cases.
- `MapUnit` : `health`, déplacement, sélection, rendu et soins déjà en place.
- `UnitActions` : point d'entrée des actions d'unité, où se brancheront les quatre postures.

Points à fixer au moment du plan, non encore vérifiés : le point d'accrochage exact dans la boucle de tour de `GameInfo` pour une résolution simultanée, et la façon d'interdire l'attaque terrestre (`TargetHelper`).

## 6. À neutraliser dans la tranche

Mécaniques du fork qui présupposent une garnison, donc incohérentes sans elle. À désactiver ou à adapter dans le scénario :

- la défense de ville fondée sur la garnison (`CityCombatant`) et sa capture par attaque directe ;
- la production prioritaire de garnison par l'IA (`ConstructionAutomation`) ;
- l'attrition de garnison par stress culturel et la pacification par garnison ;
- tous les types d'unités terrestres existants, rendus non constructibles dans le ruleset du scénario.

## 7. Interface minimale

- Sélection du jeton : mécanisme existant.
- Quatre boutons de posture dans la barre d'actions d'unité, la posture courante en surbrillance.
- À la sélection, la zone est surlignée ; les cases pressées sont marquées ; la progression d'une case se lit sur un indicateur.
- Un résumé par division : santé, posture, nombre de cases pressées, ratio estimé sur la case la plus disputée.

## 8. IA de la tranche

Rudimentaire, suffisante pour jouer contre :

1. recruter jusqu'à un effectif de divisions proportionnel à la longueur de sa frontière avec l'ennemi, dans la limite de l'or disponible ;
2. envoyer chaque division sur la case alliée la plus proche de l'objectif de guerre (`WarGoal`), par le chemin le plus court ;
3. choisir la posture à partir du ratio estimé par la **même formule** que la résolution : agressive au-dessus de 2, modérée au-dessus de 1,2, défensive entre 0,8 et 1,2, repli en dessous de 0,8 si la santé est inférieure à 40.

## 9. Scénario et mesures

- **Scénario** : une petite carte symétrique, deux civilisations de même ère, trois villes chacune, sans marine ni aviation.
- **Commande** de mise en place dans la console de développement (ou paramètre du harnais `TestAi`), graine fixe.
- **Mesures** par partie de 150 tours : cases changées de main par tour, divisions dissoutes, tours avant la première ville prise, part du temps passée dans chaque posture.
- **Contrôle d'équilibre** : à égalité de départ, deux IA identiques doivent aboutir à un résultat qui dépend de la graine, jamais d'un blocage systématique.

## 10. Tests automatisés prévus

Fonctions pures de résolution testées hors interface, avec le jeu de test existant :

- progression d'une case selon le ratio, y compris le seuil à 0,7 et la décroissance ;
- pertes à forces égales (4 points) et selon chaque couple de postures ;
- changement de propriétaire et règle anti-enclave ;
- repli : recul du jeton, retombée de la zone abandonnée ;
- retranchement : montée, plafond, remise à zéro au mouvement ;
- recrutement, renforts, dissolution ;
- déterminisme : deux résolutions identiques à graine égale.

## 11. Risques

- **Calibrage** : tous les chiffres de ce document sont des points de départ. Des runs courts à graines fixes sont indispensables ; un run complet de `TestAi` dépasse 20 heures.
- **Cohérence avec les mécaniques du fork** : la section 6 liste ce qu'il faut neutraliser. Une omission produirait des comportements absurdes (villes sans défense, attrition d'unités absentes).
- **État du fork** : trois échecs de test non expliqués sur la fusion avec l'officiel, aucune partie réelle jouée. À traiter avant de juger la tranche.
- **Lisibilité** : si le joueur ne comprend pas pourquoi une case tombe, le mode échoue même avec de bons chiffres. L'indicateur de pression est donc dans la tranche.

## 12. Questions ouvertes pour la suite

- Point d'effort explicite (une case visée) en plus de la position du jeton : repoussé après la tranche.
- Valeur de la zone : rayon 2 fixe, ou croissant avec l'ère.
- Gestion de la marine et de l'aviation face à des villes sans garnison.
