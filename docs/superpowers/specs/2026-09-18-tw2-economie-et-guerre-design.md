# Territorial Warfare v2 : équilibrage économique et refonte tactique

Date : 2026-09-18
Branche : `feat/tw2-gameplay-tuning`, issue de `feat/expansion-friendly-stability`

## 1. Problème traité

Deux symptômes rapportés en partie : des nations qui deviennent démesurément riches, et une guerre dont la tactique et la stratégie manquent de profondeur.

L'analyse du code montre que le premier symptôme ne vient ni d'un excès de revenus ni d'un manque de puits de dépense. Le chantier TW v2 a déjà introduit de nombreux puits (achat de tous les aménagements, entretien des routes, maintenance des technologies, défaut souverain). La cause réelle est un **plafond de débit de conversion indexé sur la mauvaise variable** :

```kotlin
// core/src/com/unciv/logic/automation/civilization/ImprovementPurchaseAutomation.kt:76-81
maxPurchases = (2 + civ.cities.size / 2).coerceAtMost(10)   // IA
budget       = ((civ.gold - 30) * 0.9f).toInt()
```

Le budget suit la richesse, le débit suit le nombre de villes. Observation dans `desktop_run.log` : les Ottomans réalisent 3 achats sur un budget autorisé de 8 583 or. Une civilisation riche relativement à sa taille est structurellement incapable de dépenser, et l'or s'accumule.

Ce lot ne traite pas directement ce plafond. Il agit sur les contreparties de la richesse et sur la profondeur tactique, conformément aux arbitrages retenus.

## 2. Périmètre

Cinq modifications bornées et un sous-système nouveau.

| Réf | Objet | Nature |
|---|---|---|
| E1 | Trésor à utilité décroissante dans l'indice de stabilité impériale | Borné |
| E2 | Un tour de délai sur les bâtiments achetés | Borné |
| G1 | Malus de ligne de ravitaillement au combat | Borné |
| G2 | Retraite tactique sous 30 points de vie de dégâts | Borné |
| V1 | Bonus défensif urbain porté à +200 % | Borné |
| G3 | Objectifs de guerre | Nouveau sous-système |

Hors périmètre : l'anti-hégémonie économique, l'entretien généralisé des aménagements, le débridage du débit de conversion, la coordination d'armée. Ces pistes restent ouvertes.

## 3. E1 : trésor à utilité décroissante

Fichier : `core/src/com/unciv/logic/civilization/managers/ImperialStabilityManager.kt`, fonction `getISIBreakdown()`.

État actuel : `gold > 500` accorde `+2` de stabilité, que le trésor soit de 500 ou de 50 000. La richesse est un bonus net sans contrepartie.

Remplacement par un ratio exprimant le nombre de tours de revenu thésaurisés :

```
revenuRéférence = max(goldPerTurn, 10 + 5 × nbVilles)
ratio           = gold / revenuRéférence

ratio < 2        →  0   (aucune réserve)
2  ≤ ratio < 10  → +2   (réserve saine)
10 ≤ ratio < 20  →  0   (thésaurisation neutre)
ratio ≥ 20       → -2, puis -1 par tranche de 10, plancher -8
```

Le dénominateur plancher est indispensable : sans lui, une civilisation à revenu nul ou négatif verrait son ratio diverger et récolterait la pénalité maximale alors qu'elle est déjà en difficulté.

La ligne `Positive income` existante est conservée telle quelle.

## 4. E2 : un tour de délai sur les bâtiments achetés

Fichiers : `core/src/com/unciv/logic/city/CityConstructions.kt` et `core/src/com/unciv/logic/city/managers/CityTurnManager.kt`.

Le mécanisme existe déjà pour les aménagements de terrain, via `Civilization.pendingPurchaseTiles` traité dans `TurnManager.kt`. La conception reprend le même schéma au niveau de la ville.

- Nouveau champ sérialisé `pendingPurchasedBuildings: HashSet<String>` dans `CityConstructions`, ajouté à `clone()`. Une map vide par défaut assure la rétrocompatibilité des sauvegardes existantes.
- Dans `purchaseConstruction()`, la branche `construction is Building` enregistre le nom au lieu d'appeler `construct()` immédiatement. L'or est débité dans le même tour.
- Dans `CityTurnManager.startTurn()`, chaque bâtiment en attente est effectivement ajouté, puis la liste est vidée.
- La branche `construction is BaseUnit` reste inchangée : les unités achetées restent disponibles immédiatement.

Décisions de conception :

- Ville capturée entre l'achat et la livraison : le bâtiment en attente est perdu et l'or n'est pas remboursé. La file est vidée au changement de propriétaire.
- Les merveilles suivent la même règle. En cas de course entre deux civilisations sur le même tour, le premier à avoir payé l'emporte.

## 5. G1 : ligne de ravitaillement

Fichiers : `core/src/com/unciv/logic/battle/CombatCostCalculator.kt` et `core/src/com/unciv/logic/battle/BattleDamage.kt`.

La fonction `computeDistanceFactor()` calcule déjà la distance à la ville amie la plus proche, divisée par deux en présence d'une route. Elle est aujourd'hui utilisée pour facturer le coût logistique en or. Elle est désormais également exposée pour alimenter un modificateur de puissance.

Barème appliqué dans `getGeneralModifiers()` sous la clé `Supply line` :

| Distance effective | Malus |
|---|---|
| 4 et moins | 0 % |
| 5 à 7 | ‑10 % |
| 8 à 11 | ‑25 % |
| 12 et plus | ‑40 % |

La route divisant la distance par deux, elle reste le levier stratégique principal pour projeter la force au loin.

Interaction avec l'encerclement : le malus d'encerclement existant vaut ‑75 %. Les deux ne s'additionnent pas, seul le plus pénalisant est retenu, faute de quoi une unité cumulant les deux atteindrait ‑115 % et deviendrait totalement inoffensive.

Les unités barbares et les unités navales sont exclues : les premières n'ont pas de villes de rattachement, les secondes ne dépendent pas d'une logistique terrestre.

## 6. G2 : retraite tactique

Fichier : `core/src/com/unciv/logic/battle/Battle.kt`.

Le moteur possède déjà `doWithdrawFromMeleeAbility()`, qui sait choisir une case de repli valide (en privilégiant celles hors de portée de l'attaquant, en interdisant la mer aux unités terrestres et les villes ennemies) puis y téléporter l'unité sans coût de mouvement. Cette sélection est extraite et réutilisée.

Règle : dans `takeDamage()`, avant application des dégâts, si le défenseur subirait 30 points de vie ou davantage et qu'une case de repli existe, alors les dégâts qu'il subit sont divisés par deux et il se replie.

Exclusions :

- **Garnison d'un centre-ville.** Depuis la refonte TW v2, la garnison constitue la défense de la ville. Une garnison qui recule livrerait la ville au premier coup sérieux. Cette exclusion est structurante.
- Unité embarquée, en garde, en escorte, ou incapable de se mouvoir. Ces exclusions existent déjà dans le code et sont conservées.
- Unités civiles, traitées par la capture.

La division par deux s'applique avant l'application des dégâts. Une unité qui aurait été détruite peut donc survivre à la retraite, ce qui est l'intention de la mécanique : décider les batailles avant l'anéantissement.

Le comportement est automatique pour le joueur comme pour l'IA. Une confirmation manuelle à chaque combat serait inutilisable.

## 7. V1 : bonus défensif urbain

Fichier : `core/src/com/unciv/logic/battle/CityCombatant.kt`, fonction `getCityStrength()`.

Le facteur appliqué à la force de la garnison passe de `1.5f` à `3.0f`, soit un bonus urbain de +200 % au lieu de +50 %. Les murs, le terrain et la fortification continuent de s'ajouter par-dessus.

Conséquence attendue : l'assaut frontal devient coûteux, et le siège en trois tours introduit par TW v2 devient la voie normale de conquête. Un calibrage avec le harnais `desktop/src/com/unciv/app/desktop/TestAi.kt` sera nécessaire pour vérifier que les cités-États ne deviennent pas imprenables.

## 8. G3 : objectifs de guerre

Sous-système nouveau, livré en deux temps.

### Version 1, dans ce lot

Un objectif unique de type conquête, fixé à la déclaration de guerre.

- Nouveau fichier `core/src/com/unciv/logic/civilization/diplomacy/WarGoal.kt` portant le type sérialisable (type d'objectif, identifiant de la cible, tour de fixation) et son évaluation.
- Champ nullable `warGoal` dans `DiplomacyManager`, donc les sauvegardes existantes se chargent sans migration.
- Fixation dans `DeclareWar.kt` : la ville visée est celle que `MotivationToAttackAutomation` juge la plus désirable et la plus accessible.
- État réévalué chaque tour : atteint, en cours, ou devenu inatteignable (ville détruite, ville déjà perdue par la cible, ou rapport de force inversé).
- Consommation dans `DiplomacyAutomation.offerPeaceTreaty()` : l'IA propose la paix dès que l'objectif est atteint ou devenu inatteignable, au lieu de s'enliser dans une guerre d'usure.
- Affichage de l'objectif dans l'écran de diplomatie, ce qui rend lisible pour le joueur ce que l'adversaire cherche à obtenir.

### Version 2, hors de ce lot

Trois types supplémentaires : tribut en or, cession de territoire branchée sur le système d'échange territorial existant, et rupture d'alliance.

## 9. Vérification

- Compilation du module `core` avant et après modification, pour distinguer les erreurs préexistantes.
- Passe de calibrage avec `TestAi.kt` recommandée avant fusion, en particulier sur le bonus urbain à +200 % et sur le seuil de retraite.

## 10. Dette de conception non traitée

Le chantier `feat/expansion-friendly-stability` porte une contradiction non arbitrée : les Colons sont rendus non constructibles dans `android/assets/jsons/Civ V - Vanilla/Units.json`, au motif que l'expansion passe désormais par l'absorption de cités-États, alors que le même chantier développe la suppression de la distance minimale de fondation et la fondation en territoire étranger. S'y ajoutent du code mort (`core/src/com/unciv/logic/map/PocketIsolationCheck.kt` n'est jamais appelé) et des appels de débogage laissés en production.

Ce point reste à trancher indépendamment de ce lot.
