# Unciv — Territorial Warfare & Economics Evolution

## Gameplay Rules & Mechanics Reference

> A total conversion mod that transforms Unciv's vanilla mechanics into a historically-grounded simulation of territorial conquest, imperial overstretch, and economic warfare. Every system interlocks: territory must be held, armies must be supplied, and empires that grow too fast will collapse.

---

## Table of Contents

1. [Territory & Conquest](#1-territory--conquest)
2. [Imperial Stability Index (ISI)](#2-imperial-stability-index-isi)
3. [Military & Combat](#3-military--combat)
4. [Economics & Production](#4-economics--production)
5. [City-States](#5-city-states)
6. [Diplomacy & AI](#6-diplomacy--ai)
7. [Technology](#7-technology)
8. [AI Difficulty Tuning](#8-ai-difficulty-tuning)

---

## 1. Territory & Conquest

### 1.1 — Military Units Claim Territory

Military units project power onto the land they cross:

| Action | Condition | Cost | Limit |
|--------|-----------|------|-------|
| **Claim neutral tile** | Unit moves through unowned tile | Free | 1 tile per unit per turn |
| **Capture enemy tile** | Unit moves through enemy tile (at war) | 10 HP | Unlimited (auto-pillage heals 50 HP) |

Claimed tiles are assigned to the nearest friendly city.

**Naval restriction:** Naval units can only claim or capture water tiles within **3 tiles** of a friendly city. Embarked land units cannot claim water tiles at all.

### 1.2 — Encirclement Auto-Capture

At the end of each turn, a BFS algorithm detects isolated pockets of territory:

- **Neutral pockets** entirely surrounded by one civilization are claimed automatically.
- **Enemy pockets** (at war) disconnected from any enemy city center are captured — unless defended by a military unit.

Pockets that touch the map edge are never auto-captured.

### 1.3 — Conquest: Only the City Center Transfers

When a city is conquered, **only the city center tile** transfers to the attacker. All other tiles are redistributed to the defeated civ's nearest remaining city. If the defeated civ has no cities left, all tiles transfer to the attacker's nearest city.

### 1.4 — Convert Conquered City to City-State

After conquest, players can convert a city into an **allied city-state** instead of annexing or puppeting it. This:
- Creates a new city-state civilization from unused nations in the ruleset
- Sets **500 influence** with the conquering civ
- Establishes peace and introduces the new city-state to all known civilizations

### 1.5 — City Founding Tile Reassignment

When founding a new city, tiles belonging to your other cities that are closer to the new city are automatically reassigned.

### 1.6 — Territory Exchange (Map Interface)

A full-screen map interface allows tile exchange with city-states and vassals. Accessed from the Diplomacy screen.

**With city-states:**
- **Take a tile:** costs 50 influence per tile
- **Give a tile:** grants 30 influence per tile

**With vassals (suzerain only):**
- The suzerain can take or give tiles freely with their vassal
- Available only when not at war with the vassal

**Interface:**
- Click an other civ's tile → red outline (take)
- Click your own tile → green outline (give)
- Re-click to deselect
- Contiguity enforced: cannot disconnect a city from its tiles
- City centers cannot be exchanged
- Confirm popup before executing transfers
- Tiles are assigned to the nearest eligible city of the receiving civilization

### 1.7 — Automatic Border Harmonization

Each turn, civilizations that are **not at war** automatically exchange border tiles to smooth out their frontiers. Tiles that are significantly closer to a neighboring civilization's city than to any of their owner's cities are gradually transferred.

**Rules:**
- A tile is considered "misplaced" if it is at least **2 tiles closer** to a neighbor's city than to its owner's nearest city
- Only border tiles (adjacent to the other civ's territory) are considered
- Up to **3 tiles** per civ pair are exchanged per turn
- Paired swaps are preferred (A gives 1 tile to B, B gives 1 tile to A); excess tiles transfer freely
- **City centers** are never exchanged
- Tiles with **military units** are not exchanged
- Tile transfers that would **break city contiguity** (disconnect tiles from their city center) are skipped
- Both sides receive a notification: "Border harmonization: exchanged X tiles with [CivName]"
- City-states participate in harmonization (they exchange tiles with major civs and other city-states)

**Effect:** Over multiple turns, borders organically align with the closest cities, reducing jagged or nonsensical frontiers without player intervention.

---

## 2. Imperial Stability Index (ISI)

The ISI is a score from **0 to 100** that measures how well a civilization can hold its territory. It is recalculated every turn for major civilizations.

### 2.1 — Stability Tiers

| Tier | ISI Range | Effects |
|------|-----------|---------|
| **Golden Age** | 80–100 | Progressive production & gold bonus (see §4.5) |
| **Stable** | 60–79 | No modifiers (default start at 70) |
| **Tensions** | 40–59 | -25% production in conquered cities |
| **Crisis** | 20–39 | -50% production in conquered cities, -20% distant combat, 5%/turn revolt chance, -5 CS influence/turn |
| **Collapse** | 0–19 | -50% production in ALL cities, 15%/turn revolt chance |

### 2.2 — ISI Calculation Factors

**Base:** +50

| Positive Factor | Value |
|-----------------|-------|
| Established city (>20 turns old) | +1 per city |
| Connected to capital (via roads) | +2 per city |
| Positive gold income | +1 |
| Gold reserves > 500 | +2 |
| At peace | +2 |
| Cultural output > cities × 5 | +1 |
| Unified religion (>75% cities) | +2 |

| Negative Factor | Value |
|-----------------|-------|
| Over-expansion (beyond 3 + era number) | -1 per excess city |
| Recent conquest (<10 turns) | -3 per city |
| City in resistance | -5 per city |
| Distant city (>15 tiles from capital) | -1 per city |
| Gold deficit | -3 |
| Multiple wars (>1 major enemy) | -2 per additional enemy |
| Military unit losses this turn | -2 per unit |
| Cultural identity (conquered cities) | -0.03 per identity point per city |
| Demographic shock | -5 per city affected this turn |

### 2.3 — Cultural Assimilation

Conquered cities have a **cultural identity** score (0-100) representing how "foreign" they feel:
- Starts at **100** when a city is conquered from a different founding civ
- Decays at **-1/turn** base rate
- **Accelerated by:** shared religion (-1 extra), connected to capital (-0.5 extra)
- **Slowed by:** war with founding civ (+0.5)
- ISI penalty: `-0.03 × culturalIdentity` per city (a city at 100 = -3 ISI, at 50 = -1.5, at 0 = none)
- Revolt priority: `+culturalIdentity/2` (max +50 for a city at 100)

This replaces the old flat "Foreign-founded cities: -1 per city" factor.

### 2.4 — Revolts & Secessions

When in **Crisis** (5% chance) or **Collapse** (15% chance) each turn, a city may revolt and become an independent city-state.

Revolt priority scoring:
- Recent conquest (<10 turns): +40
- In resistance: +30
- Distant from capital (>15 tiles): +20
- Foreign-founded: +10
- Cultural identity: +identity/2 (max +50)

The **capital never revolts**. Maximum 1 revolt per turn. After a revolt, ISI gains +15 (stability relief). Friendly units in the revolting city are teleported to nearest safe tile.

### 2.5 — Civil War

When the empire is in **Collapse** tier with **5+ cities** and **3+ eligible revolt candidates**, a Civil War triggers instead of individual revolts. This is a **one-time event** per civilization.

**Conditions:**
- ISI tier = Collapse
- 5+ cities total
- 3+ cities with cultural identity > 30, in resistance, distant from capital, or recently conquered
- No previous civil war

**Mechanics:**
1. An unused major nation from the ruleset becomes the rebel faction
2. Cities are scored by distance from capital + cultural identity + disconnection + resistance
3. The top half (most distant/foreign) become rebel cities, the rest stay loyal
4. Rebels inherit: proportional tech, policies, and gold
5. Units on rebel territory are captured
6. Rebels start at war with loyalists and inherit existing wars
7. Rebel ISI starts at 50; loyalist ISI gains +20

### 2.6 — Demographic Shocks (Plague/Famine)

When ISI < 30, there is a **2% chance per turn** of a demographic shock:

**Ground zero:** A random city without a Medical Lab
**Population loss:** -25% to -50% (minimum population: 1)
**Propagation:** 30% chance to spread to each connected city without Medical Lab
**Building protection:**
- Medical Lab = total immunity
- Hospital = damage ÷3
- Aqueduct = damage ÷2

**ISI penalty:** -5 per city affected this turn

### 2.7 — Renaissance Bonus

When ISI recovers from below 40 to 60 or above, a **Renaissance** triggers:
- Duration: **15 turns**
- Bonus: **+25% production and culture**, decreasing linearly each turn
- Formula: `25% × turnsRemaining / 15`

---

## 3. Military & Combat

### 3.1 — Adjacency Bonus

Military units gain strength from nearby allies:
- **2 adjacent friendly military units:** +20%
- **3+ adjacent friendly military units:** +40%

### 3.2 — War Experience

Civilizations at war accumulate combat experience:
- **At war:** +1%/turn (max 30%)
- **At peace:** -1%/turn (min 0%)
- Applied as a flat combat modifier to all units

### 3.3 — Imperial Instability Combat Malus

When ISI < 40 **and** the unit is more than 10 tiles from the capital: **-20% combat strength**.

### 3.4 — Unit Isolation Attrition

A military unit where **all 6 adjacent tiles** are owned by enemy civilizations suffers **50 HP damage per turn**. Units destroyed this way trigger a notification.

### 3.5 — Distance-Based Unit Maintenance

Military units cost **1 gold per tile of distance** from the nearest friendly city. Units on road-connected tiles to a city are **free** (supply lines).

### 3.6 — Worker Production Cost

Worker units (units with "Build Improvements") cost **×4 production** to build.

### 3.7 — Improvement Build Time

All tile improvements take **×2 longer** to build or repair.

### 3.8 — Kill Bonus (Replaces Promotions)

The vanilla promotion system is **disabled**. Units can no longer be promoted through XP. Instead, units gain combat power through kills:

| Event | Effect |
|-------|--------|
| **Kill a military unit** | +5% combat strength (cumulative) |
| **Each turn** | -1% decay (minimum 0%) |

- Displayed as "Kill experience" in the combat breakdown
- Stacks indefinitely (a unit with 10 kills has +50% before decay)
- Persists across turns and is saved with the unit
- Decay applies at the start of each turn

This rewards aggressive, veteran units while preventing permanent snowballing.

### 3.9 — Fishing Boats & Maritime Territory

Fishing Boats can be built **outside your borders** on unowned water tiles with resources:

| Action | Effect |
|-------|--------|
| **Build on unowned water** | Tile is automatically claimed by your nearest city |
| **Naval capture** | Military ships can capture enemy water tiles with improvements that are **>3 tiles from any enemy city** (at war) |
| **Embarked restriction** | Embarked land units **cannot** capture maritime territory |

### 3.10 — War & Peace Timers

| Timer | Duration |
|-------|----------|
| **Minimum war duration** | 5 turns (cannot make peace before) |
| **Peace treaty duration** | 7 turns (cannot declare war again before) |

---

## 4. Economics & Production

### 4.0 — Population-Based Production Model

Production is calculated from **population**, not tile shields:

**Formula:**
```
Base Production = Population × 1 + Building Production + Specialist Production
Tile Bonus = Sum of worked tile shields → converted to % bonus (1 shield = +1%)
Final Production = Base Production × (1 + Tile Bonus% + other bonuses%)
```

| Source | Contribution |
|--------|-------------|
| **Population** | 1 production per citizen (ALL citizens, not just unassigned) |
| **Buildings** | Direct production (additive to base) |
| **Specialists** | Direct production (additive to base) |
| **Tile shields** | % bonus only (1 hammer = +1% production) |

**Example:** City pop 10, buildings +3, 12 tile shields:
- Base = 10 + 3 = 13 production
- Tile bonus = +12%
- Final (before other bonuses) = 13 × 1.12 = **14.6**

Tiles still provide gold, culture, science, and faith as direct yields — only production changes.

### 4.1 — Trade Route Gold

Connected cities generate **×3 trade route gold** compared to vanilla.
Formula: `(capitalPop × 0.15 + cityPop × 1.1 - 1) × 3`

### 4.2 — War/Peace Military Production

| State | Military Production Modifier |
|-------|------------------------------|
| At war | +100% (×2) |
| At peace | -50% (×0.5) |

### 4.3 — Industrial Era Logistic Growth

In the Industrial era, production follows a **logistic sigmoid curve** that scales with time spent in the era:

```
multiplier = (-115 + 430 / (1 + e^(-0.0065 × (turns - 1)))) / 100
```

This creates an S-curve: slow ramp-up, rapid growth, then plateau.

### 4.4 — Conquest & Expansion Production Malus

Expanding your empire comes at a production cost:

| Factor | Malus | Recovery |
|--------|-------|----------|
| **Conquered city** | -30% production in that city | +0.5%/turn (60 turns to full recovery) |
| **Expansion** | -5% production per city beyond the first | Permanent (structural overhead) |

- **Conquest malus** applies to any city founded by a different civilization (including reconquered cities). It represents the economic disruption of integrating foreign territories.
- **Expansion malus** is global: every city in the empire suffers -5% production for each city beyond the first. A 4-city empire has -15% production in all cities. This models administrative overhead.
- Both maluses stack with ISI tier penalties.

### 4.5 — Capital Proximity Production Bonus

From the **Medieval era** onwards, proximity to other civilizations' capitals grants a cumulative production bonus to all your cities:

**Formula per capital:** `Bonus = 100 - (distance × 10)` (minimum 0%)

| Distance (tiles) | Bonus |
|-------------------|-------|
| 1 | +90% |
| 4 | +60% |
| 6 | +40% |
| 10+ | +0% |

- **Cumulative:** bonuses from all nearby capitals are summed. Example: one capital at 4 tiles (+60%) and another at 6 tiles (+40%) = +100% total bonus.
- Applies to **production and science**.
- **Conditions for a capital to count:** the capital must still belong to its founding civilization. If the capital is captured by another civ or converted to a city-state, the bonus disappears.
- Only applies to **major civilizations** (not city-states).
- Represents the economic benefits of trade, cultural exchange, and competition between nearby empires.

### 4.6 — Tech Maintenance & Regression

Every researched technology costs **1% of its base research cost per turn** in science to maintain. If a civilization cannot generate enough science to cover this cost:

1. **Gold-to-science conversion:** Excess gold is automatically converted 1:1 to cover the science deficit.
2. **Tech regression:** If science remains negative even after gold conversion, the civilization **loses its most advanced technology** each turn.

**Details:**
- Maintenance cost = `Σ (tech.baseCost × 0.01)` for each researched tech, scaled by game speed
- Only non-repeatable technologies count
- Tech regression removes the most advanced tech that is not a prerequisite for another researched tech
- This creates a natural ceiling on technological advancement proportional to a civilization's scientific output
- A player must balance expanding their tech tree with maintaining sufficient science infrastructure

### 4.7 — Golden Age Progressive Bonus

The Golden Age (ISI 80+) grants a **progressive production and gold bonus** that ramps up, plateaus, then fades:

| Phase | Duration | Bonus |
|-------|----------|-------|
| **Ramp-up** | First 5 turns | +2% per turn elapsed (2% → 4% → 6% → 8% → 10%) |
| **Plateau** | Middle turns | +10% production and gold |
| **Ramp-down** | Last 5 turns | -2% per turn remaining (10% → 8% → 6% → 4% → 2%) |

- Applies to **production** and **gold** (not culture).
- A 10-turn golden age: 5 turns ramp-up → 0 turns plateau → 5 turns ramp-down.
- A 20-turn golden age: 5 turns ramp-up → 10 turns plateau → 5 turns ramp-down.
- This prevents sudden economic spikes and encourages longer golden ages.

### 4.8 — Production Growth Cap (+5%/turn)

A city's production **cannot increase by more than 5% per turn** compared to the previous turn's output. This smooths out sudden production spikes from new buildings, tiles, or population growth.

- **Increases are capped:** if production would rise from 20 to 30, it is capped at 21 (20 × 1.05). The excess is shown as "Production growth cap" in the stat breakdown.
- **Decreases are instant:** if production drops (e.g., losing a building, conquest penalty, ISI crisis), the full reduction applies immediately with no smoothing.
- **New cities** start uncapped (no previous production recorded).
- The cap applies after all other modifiers (bonuses, maluses, AI multipliers) but before the minimum production floor of 1.

**Effect:** Production curves become gradual, preventing explosive jumps when a city acquires a key building or resource. Crises, however, hit immediately and brutally.

---

## 5. City-States

| Mechanic | Value |
|----------|-------|
| **Population growth speed** | ×3 faster (food requirements ÷3) |
| **Military production bonus** | +200% (×3 total) |
| **Liberation influence reward** | 500 (vanilla: ~90) |
| **Convert-to-CS influence** | 500 |

---

## 6. Diplomacy & AI

### 6.0 — Vassalage System

When a civilization is sufficiently weakened, the conqueror can choose to **vassalize** them instead of annexing/puppeting. The defeated civ gets the city back but becomes a vassal.

**Setting up vassalage:**
- Option appears in the conquest popup when **all conditions are met**:
  - The defeated civ has **3 cities or fewer**
  - The conquered city **is the original capital**, OR the defeated civ **no longer owns their original capital** (already captured)
  - The defeated civ is a **major civilization** (not a city-state)
  - The conqueror is **not already a vassal** themselves
- A vassal cannot create other vassals
- The city is returned to the defeated civ with full conquest effects (plunder, population loss, resistance)
- Peace is established between suzerain and vassal with a peace treaty

**Diplomatic restrictions:**
| Rule | Effect |
|------|--------|
| **War declaration** | Vassals cannot declare war independently |
| **War protection** | No one can declare war on a vassal directly (must target suzerain) |
| **War following** | Vassals automatically join all suzerain's wars |
| **Peace following** | Vassals automatically make peace when suzerain does |

**Economic tribute (25%):**
| Resource | Amount | Condition |
|----------|--------|-----------|
| **Gold** | 25% of vassal's net gold income | Only when income > 0 |
| **Science** | 25% of vassal's net science income | Only when income > 0 |

Tribute is visible in the stat breakdown as "Vassal tribute" (vassal) and "Vassal income" (suzerain).

**Independence war:**
- Requires vassal's military might >= 25% of suzerain's
- AI vassals automatically declare independence when condition is met
- Human vassals must wait for the condition (future UI button)

**Territory exchange:**
- The suzerain can exchange tiles with their vassal via a dedicated map screen (Diplomacy → Territory Exchange)
- Take vassal tiles or give own tiles freely — no influence cost
- Contiguity enforced: cannot leave a city disconnected from its tiles

**Automatic liberation:**
- If the suzerain is defeated or destroyed, all vassals are automatically freed

### 6.1 — ISI Influence on City-States

When a major civ's ISI drops below 40, it loses **5 influence per turn** with every city-state (applied only when influence > 0).

### 6.2 — AI War Declaration

- AI civs with **ISI < 40** on the target gain up to **+10 motivation** to attack: `(40 - targetISI) / 4`
- AI civs that are **themselves unstable** get a motivation penalty: `-5 × personality factor`
- Unhappy civs can still declare war if their **ISI < 40** (desperation wars)

---

## 7. Technology

| State | Military Techs | Civilian Techs |
|-------|----------------|----------------|
| At war | **÷2 cost** | **×2 cost** |
| At peace | **×2 cost** | Normal |

---

## 8. AI Difficulty Tuning

An in-game popup (Menu → **AI Bonuses**) provides four sliders to adjust AI civilization bonuses in real time:

| Stat | Range | Default | Effect |
|------|-------|---------|--------|
| **Production** | 90% – 300% | 100% | Multiplies all AI city production output |
| **Growth** | 90% – 300% | 100% | Multiplies all AI city food output |
| **Gold** | 90% – 300% | 100% | Multiplies all AI city gold output |
| **Science** | 90% – 300% | 100% | Multiplies all AI city science output |

- Step size: 5%
- Applied as a final multiplier on all non-human city stat calculations
- Saved in the game file (persists across save/load)
- Changes take effect on the next turn (or immediately on close for all AI cities)
- Stacks with existing difficulty modifiers

---

---

# Unciv — Territorial Warfare & Economics Evolution

## Regles de jeu et reference des mecaniques (FR)

> Un mod de conversion totale qui transforme les mecaniques vanilla d'Unciv en une simulation historiquement fondee de conquete territoriale, de surextension imperiale et de guerre economique. Chaque systeme s'imbrique : le territoire doit etre tenu, les armees approvisionnees, et les empires qui grandissent trop vite s'effondrent.

---

## Sommaire

1. [Territoire et Conquete](#1-territoire-et-conquete)
2. [Indice de Stabilite Imperiale (ISI)](#2-indice-de-stabilite-imperiale-isi)
3. [Militaire et Combat](#3-militaire-et-combat)
4. [Economie et Production](#4-economie-et-production)
5. [Cites-Etats](#5-cites-etats)
6. [Diplomatie et IA](#6-diplomatie-et-ia)
7. [Technologie](#7-technologie)
8. [Reglage de difficulte IA](#8-reglage-de-difficulte-ia)

---

## 1. Territoire et Conquete

### 1.1 — Les unites militaires revendiquent le territoire

Les unites militaires projettent leur puissance sur les terres qu'elles traversent :

| Action | Condition | Cout | Limite |
|--------|-----------|------|--------|
| **Revendiquer une tuile neutre** | L'unite traverse une tuile sans proprietaire | Gratuit | 1 tuile par unite par tour |
| **Capturer une tuile ennemie** | L'unite traverse une tuile ennemie (en guerre) | 10 PV | Illimite (le pillage automatique soigne 50 PV) |

Les tuiles revendiquees sont attribuees a la ville amie la plus proche.

**Restriction navale :** Les unites navales ne peuvent revendiquer ou capturer des tuiles maritimes que dans un rayon de **3 cases** d'une ville amie. Les unites terrestres embarquees ne peuvent pas revendiquer de tuiles maritimes.

### 1.2 — Capture automatique par encerclement

A la fin de chaque tour, un algorithme BFS detecte les poches de territoire isolees :

- **Poches neutres** entierement entourees par une civilisation sont revendiquees automatiquement.
- **Poches ennemies** (en guerre) deconnectees de tout centre-ville ennemi sont capturees — sauf si une unite militaire les defend.

Les poches touchant le bord de la carte ne sont jamais capturees.

### 1.3 — Conquete : seul le centre-ville est transfere

Lors d'une conquete, **seule la tuile du centre-ville** est transferee a l'attaquant. Toutes les autres tuiles sont redistribuees a la ville la plus proche de la civilisation vaincue. Si cette derniere n'a plus de villes, tout le territoire revient a la ville la plus proche du vainqueur.

### 1.4 — Convertir une ville conquise en cite-etat

Apres une conquete, les joueurs peuvent convertir une ville en **cite-etat alliee** au lieu de l'annexer ou d'en faire une marionnette. Cela :
- Cree une nouvelle civilisation cite-etat a partir des nations inutilisees
- Etablit **500 d'influence** avec le conquerant
- Fait la paix et presente la nouvelle cite-etat a toutes les civilisations connues

### 1.5 — Reassignation des tuiles a la fondation d'une ville

Lorsqu'une nouvelle ville est fondee, les tuiles appartenant a vos autres villes qui sont plus proches de la nouvelle ville sont automatiquement reassignees.

### 1.6 — Echange de territoire (Interface carte)

Une interface carte plein ecran permet l'echange de tuiles avec les cites-etats et les vassaux. Accessible depuis l'ecran Diplomatie.

**Avec les cites-etats :**
- **Prendre une tuile :** coute 50 d'influence par tuile
- **Donner une tuile :** rapporte 30 d'influence par tuile

**Avec les vassaux (suzerain uniquement) :**
- Le suzerain peut prendre ou donner des tuiles librement a son vassal
- Disponible uniquement quand pas en guerre avec le vassal

**Interface :**
- Clic sur une tuile de l'autre civ → contour rouge (prendre)
- Clic sur une de vos tuiles → contour vert (donner)
- Re-clic pour deselectioner
- Contiguite imposee : impossible de deconnecter une ville de ses tuiles
- Les centres-villes ne peuvent pas etre echanges
- Popup de confirmation avant execution des transferts
- Les tuiles sont assignees a la ville eligible la plus proche de la civilisation receptrice

### 1.7 — Harmonisation automatique des frontieres

Chaque tour, les civilisations qui ne sont **pas en guerre** echangent automatiquement des tuiles frontalieres pour lisser leurs frontieres. Les tuiles significativement plus proches d'une ville voisine que de toute ville de leur proprietaire sont progressivement transferees.

**Regles :**
- Une tuile est consideree "mal placee" si elle est au moins **2 tuiles plus proche** d'une ville voisine que de la ville la plus proche de son proprietaire
- Seules les tuiles frontalieres (adjacentes au territoire de l'autre civ) sont considerees
- Jusqu'a **3 tuiles** par paire de civs sont echangees par tour
- Les echanges apparies sont privilegies (A donne 1 tuile a B, B donne 1 tuile a A) ; les tuiles excedentaires sont transferees librement
- Les **centres-villes** ne sont jamais echanges
- Les tuiles avec des **unites militaires** ne sont pas echangees
- Les transferts qui **casseraient la contiguite** (deconnecteraient des tuiles de leur centre-ville) sont ignores
- Les deux parties recoivent une notification : "Harmonisation des frontieres : echange de X tuiles avec [NomCiv]"
- Les cites-etats participent a l'harmonisation (elles echangent des tuiles avec les civilisations majeures et d'autres cites-etats)

**Effet :** Au fil des tours, les frontieres s'alignent organiquement avec les villes les plus proches, reduisant les frontieres decoupees ou illogiques sans intervention du joueur.

---

## 2. Indice de Stabilite Imperiale (ISI)

L'ISI est un score de **0 a 100** qui mesure la capacite d'une civilisation a maintenir son territoire. Il est recalcule chaque tour pour les civilisations majeures.

### 2.1 — Niveaux de stabilite

| Niveau | Plage ISI | Effets |
|--------|-----------|--------|
| **Age d'Or** | 80–100 | Bonus progressif production et or (voir §4.5) |
| **Stable** | 60–79 | Aucun modificateur (depart par defaut a 70) |
| **Tensions** | 40–59 | -25% production dans les villes conquises |
| **Crise** | 20–39 | -50% production villes conquises, -20% combat lointain, 5%/tour chance de revolte, -5 influence CS/tour |
| **Effondrement** | 0–19 | -50% production TOUTES les villes, 15%/tour chance de revolte |

### 2.2 — Facteurs de calcul de l'ISI

**Base :** +50

| Facteur positif | Valeur |
|-----------------|--------|
| Ville etablie (>20 tours) | +1 par ville |
| Connectee a la capitale (par routes) | +2 par ville |
| Revenu d'or positif | +1 |
| Reserves d'or > 500 | +2 |
| En paix | +2 |
| Production culturelle > villes x 5 | +1 |
| Religion unifiee (>75% des villes) | +2 |

| Facteur negatif | Valeur |
|-----------------|--------|
| Surexpansion (au-dela de 3 + numero d'ere) | -1 par ville en exces |
| Conquete recente (<10 tours) | -3 par ville |
| Ville en resistance | -5 par ville |
| Ville distante (>15 tuiles de la capitale) | -1 par ville |
| Deficit d'or | -3 |
| Guerres multiples (>1 ennemi majeur) | -2 par ennemi supplementaire |
| Pertes militaires ce tour | -2 par unite |
| Identite culturelle (villes conquises) | -0.03 par point d'identite par ville |
| Choc demographique | -5 par ville touchee ce tour |

### 2.3 — Assimilation Culturelle

Les villes conquises ont un score d'**identite culturelle** (0-100) representant leur degre d'« etrangete » :
- Commence a **100** quand une ville est conquise d'une civilisation fondatrice differente
- Decroit a **-1/tour** (taux de base)
- **Accelere par :** religion partagee (-1 extra), connexion a la capitale (-0.5 extra)
- **Ralenti par :** guerre avec la civilisation fondatrice (+0.5)
- Penalite ISI : `-0.03 x identiteCulturelle` par ville (ville a 100 = -3 ISI, a 50 = -1.5, a 0 = rien)
- Priorite de revolte : `+identiteCulturelle/2` (max +50 pour une ville a 100)

Remplace l'ancien facteur « Villes fondees par d'autres : -1 par ville ».

### 2.4 — Revoltes et Secessions

En **Crise** (5% par tour) ou **Effondrement** (15% par tour), une ville peut se revolter et devenir une cite-etat independante.

Score de priorite de revolte :
- Conquete recente (<10 tours) : +40
- En resistance : +30
- Distante de la capitale (>15 tuiles) : +20
- Fondee par un autre : +10
- Identite culturelle : +identite/2 (max +50)

La **capitale ne se revolte jamais**. Maximum 1 revolte par tour. Apres une revolte, l'ISI gagne +15 (soulagement). Les unites amies dans la ville revoltee sont teleportees a la tuile sure la plus proche.

### 2.5 — Guerre Civile

Quand l'empire est en **Effondrement** avec **5+ villes** et **3+ candidats a la revolte eligibles**, une Guerre Civile se declenche au lieu de revoltes individuelles. C'est un **evenement unique** par civilisation.

**Conditions :**
- Niveau ISI = Effondrement
- 5+ villes au total
- 3+ villes avec identite culturelle > 30, en resistance, distantes de la capitale, ou conquises recemment
- Pas de guerre civile precedente

**Mecaniques :**
1. Une nation majeure inutilisee du ruleset devient la faction rebelle
2. Les villes sont classees par distance a la capitale + identite culturelle + deconnexion + resistance
3. La moitie superieure (les plus distantes/etrangeres) devient rebelle, le reste reste loyaliste
4. Les rebelles heritent : tech, politiques, et or proportionnel
5. Les unites sur le territoire rebelle sont capturees
6. Les rebelles commencent en guerre avec les loyalistes et heritent des guerres existantes
7. ISI rebelle = 50 ; ISI loyaliste +20

### 2.6 — Chocs Demographiques (Peste/Famine)

Quand l'ISI < 30, il y a **2% de chance par tour** qu'un choc demographique se produise :

**Ville ground zero :** une ville aleatoire sans Medical Lab
**Perte de population :** -25% a -50% (population minimum : 1)
**Propagation :** 30% de chance de se propager a chaque ville connectee sans Medical Lab
**Protection des batiments :**
- Medical Lab = immunite totale
- Hospital = degats /3
- Aqueduct = degats /2

**Penalite ISI :** -5 par ville touchee ce tour

### 2.7 — Bonus de Renaissance

Quand l'ISI remonte de moins de 40 a 60 ou plus, une **Renaissance** se declenche :
- Duree : **15 tours**
- Bonus : **+25% production et culture**, decroissant lineairement chaque tour
- Formule : `25% x toursRestants / 15`

---

## 3. Militaire et Combat

### 3.1 — Bonus d'adjacence

Les unites militaires gagnent en force grace aux allies proches :
- **2 unites militaires amies adjacentes :** +20%
- **3+ unites militaires amies adjacentes :** +40%

### 3.2 — Experience de guerre

Les civilisations en guerre accumulent de l'experience de combat :
- **En guerre :** +1%/tour (max 30%)
- **En paix :** -1%/tour (min 0%)
- Applique comme modificateur de combat plat a toutes les unites

### 3.3 — Malus de combat d'instabilite imperiale

Quand l'ISI < 40 **et** que l'unite est a plus de 10 tuiles de la capitale : **-20% de force de combat**.

### 3.4 — Attrition par isolement

Une unite militaire dont **les 6 tuiles adjacentes** appartiennent toutes a des ennemis subit **50 PV de degats par tour**. Les unites detruites ainsi declenchent une notification.

### 3.5 — Entretien des unites base sur la distance

Les unites militaires coutent **1 or par tuile de distance** de la ville amie la plus proche. Les unites sur des tuiles connectees par route a une ville sont **gratuites** (lignes de ravitaillement).

### 3.6 — Cout de production des travailleurs

Les travailleurs (unites "Ameliorer les tuiles") coutent **x4 en production** a construire.

### 3.7 — Temps de construction des ameliorations

Toutes les ameliorations de tuiles prennent **x2 plus de temps** a construire ou reparer.

### 3.8 — Bonus de mise a mort (Remplace les promotions)

Le systeme de promotions vanilla est **desactive**. Les unites ne peuvent plus etre promues par l'XP. A la place, les unites gagnent en puissance de combat par les eliminations :

| Evenement | Effet |
|-----------|-------|
| **Eliminer une unite militaire** | +5% de force de combat (cumulatif) |
| **Chaque tour** | -1% de decroissance (minimum 0%) |

- Affiche comme "Kill experience" dans le detail du combat
- Se cumule indefiniment (une unite avec 10 eliminations a +50% avant decroissance)
- Persiste entre les tours et est sauvegarde avec l'unite
- La decroissance s'applique au debut de chaque tour

Cela recompense les unites veteranes agressives tout en empechant l'effet boule de neige permanent.

### 3.9 — Bateaux de peche et territoire maritime

Les bateaux de peche peuvent etre construits **hors de vos frontieres** sur des tuiles maritimes sans proprietaire avec des ressources :

| Action | Effet |
|--------|-------|
| **Construire sur eau sans proprietaire** | La tuile est automatiquement revendiquee par votre ville la plus proche |
| **Capture navale** | Les navires militaires peuvent capturer les tuiles maritimes ennemies avec ameliorations a **>3 cases de toute ville ennemie** (en guerre) |
| **Restriction embarque** | Les unites terrestres embarquees **ne peuvent pas** capturer de territoire maritime |

### 3.10 — Durees de guerre et de paix

| Minuterie | Duree |
|-----------|-------|
| **Duree minimale de guerre** | 5 tours (impossible de faire la paix avant) |
| **Duree du traite de paix** | 7 tours (impossible de redeclarer la guerre avant) |

---

## 4. Economie et Production

### 4.0 — Modele de production base sur la population

La production est calculee a partir de la **population**, pas des boucliers de tuiles :

**Formule :**
```
Production de base = Population x 1 + Production des batiments + Production des specialistes
Bonus tuiles = Somme des boucliers des tuiles travaillees → converti en bonus % (1 bouclier = +1%)
Production finale = Production de base x (1 + Bonus tuiles% + autres bonus%)
```

| Source | Contribution |
|--------|-------------|
| **Population** | 1 production par citoyen (TOUS les citoyens, pas seulement les non-assignes) |
| **Batiments** | Production directe (additif a la base) |
| **Specialistes** | Production directe (additif a la base) |
| **Boucliers de tuiles** | Bonus % uniquement (1 marteau = +1% de production) |

**Exemple :** Ville pop 10, batiments +3, 12 boucliers de tuiles :
- Base = 10 + 3 = 13 production
- Bonus tuiles = +12%
- Final (avant autres bonus) = 13 x 1.12 = **14.6**

Les tuiles continuent d'apporter or, culture, science et foi normalement — seule la production change.

### 4.1 — Or des routes commerciales

Les villes connectees generent **x3 l'or des routes commerciales** par rapport au vanilla.
Formule : `(popCapitale x 0.15 + popVille x 1.1 - 1) x 3`

### 4.2 — Production militaire guerre/paix

| Etat | Modificateur de production militaire |
|------|--------------------------------------|
| En guerre | +100% (x2) |
| En paix | -50% (x0.5) |

### 4.3 — Croissance logistique de l'ere industrielle

A l'ere industrielle, la production suit une **courbe sigmoide logistique** qui evolue avec le temps passe dans l'ere :

```
multiplicateur = (-115 + 430 / (1 + e^(-0.0065 x (tours - 1)))) / 100
```

Cela cree une courbe en S : montee lente, croissance rapide, puis plateau.

### 4.4 — Malus de production de conquete et d'expansion

L'expansion de l'empire a un cout en production :

| Facteur | Malus | Recuperation |
|---------|-------|--------------|
| **Ville conquise** | -30% production dans cette ville | +0.5%/tour (60 tours pour recuperation totale) |
| **Expansion** | -5% production par ville au-dela de la premiere | Permanent (surcout administratif) |

- **Malus de conquete** s'applique a toute ville fondee par une autre civilisation (y compris les villes reconquises). Il represente la perturbation economique de l'integration de territoires etrangers.
- **Malus d'expansion** est global : chaque ville de l'empire subit -5% de production pour chaque ville au-dela de la premiere. Un empire de 4 villes a -15% de production dans toutes ses villes. Cela modelise le surcout administratif.
- Les deux malus se cumulent avec les penalites de niveau ISI.

### 4.5 — Bonus de production par proximite des capitales

A partir du **Moyen Age**, la proximite des capitales d'autres civilisations accorde un bonus de production cumulatif a toutes vos villes :

**Formule par capitale :** `Bonus = 100 - (distance x 10)` (minimum 0%)

| Distance (cases) | Bonus |
|-------------------|-------|
| 1 | +90% |
| 4 | +60% |
| 6 | +40% |
| 10+ | +0% |

- **Cumulatif :** les bonus de toutes les capitales proches sont additionnes. Exemple : une capitale a 4 cases (+60%) et une autre a 6 cases (+40%) = +100% de bonus total.
- S'applique a la **production** et la **science**.
- **Conditions pour qu'une capitale compte :** la capitale doit toujours appartenir a sa civilisation fondatrice. Si la capitale est capturee par une autre civilisation ou convertie en cite-etat, le bonus disparait.
- S'applique uniquement aux **civilisations majeures** (pas aux cites-etats).
- Represente les benefices economiques du commerce, des echanges culturels et de la competition entre empires voisins.

### 4.6 — Maintenance technologique et regression

Chaque technologie recherchee coute **1% de son cout de recherche de base par tour** en science pour etre maintenue. Si une civilisation ne peut pas generer assez de science :

1. **Conversion or → science :** L'or excedentaire est automatiquement converti 1:1 pour couvrir le deficit de science.
2. **Regression technologique :** Si la science reste negative meme apres conversion de l'or, la civilisation **perd sa technologie la plus avancee** chaque tour.

**Details :**
- Cout de maintenance = `Σ (tech.coutDeBase x 0.01)` pour chaque tech recherchee, ajuste par la vitesse de jeu
- Seules les technologies non-repetables comptent
- La regression retire la tech la plus avancee qui n'est pas prerequis d'une autre tech recherchee
- Cela cree un plafond naturel d'avancement technologique proportionnel a la production scientifique
- Le joueur doit equilibrer l'expansion de son arbre technologique avec le maintien d'infrastructures scientifiques suffisantes

### 4.7 — Bonus progressif de l'Age d'Or

L'Age d'Or (ISI 80+) accorde un **bonus progressif de production et d'or** qui monte, stagne, puis decroit :

| Phase | Duree | Bonus |
|-------|-------|-------|
| **Montee** | 5 premiers tours | +2% par tour ecoule (2% → 4% → 6% → 8% → 10%) |
| **Plateau** | Tours centraux | +10% production et or |
| **Descente** | 5 derniers tours | -2% par tour restant (10% → 8% → 6% → 4% → 2%) |

- S'applique a la **production** et l'**or** (pas la culture).
- Un age d'or de 10 tours : 5 tours de montee → 0 tours de plateau → 5 tours de descente.
- Un age d'or de 20 tours : 5 tours de montee → 10 tours de plateau → 5 tours de descente.
- Cela evite les pics economiques soudains et encourage les ages d'or plus longs.

### 4.8 — Plafonnement de croissance de production (+5%/tour)

La production d'une ville **ne peut pas augmenter de plus de 5% par tour** par rapport a la production du tour precedent. Cela lisse les pics soudains de production lies aux nouveaux batiments, tuiles ou croissance de population.

- **Les augmentations sont plafonnees :** si la production passerait de 20 a 30, elle est plafonnee a 21 (20 x 1.05). L'exces est affiche comme « Production growth cap » dans le detail des stats.
- **Les baisses sont immediates :** si la production chute (perte d'un batiment, malus de conquete, crise ISI), la reduction complete s'applique instantanement sans lissage.
- **Nouvelles villes** : demarrent sans plafond (aucune production precedente enregistree).
- Le plafond s'applique apres tous les autres modificateurs (bonus, malus, multiplicateurs IA) mais avant le plancher minimum de production de 1.

**Effet :** Les courbes de production deviennent graduelles, empechant les sauts explosifs quand une ville acquiert un batiment ou une ressource cle. Les crises, en revanche, frappent immediatement et brutalement.

---

## 5. Cites-Etats

| Mecanique | Valeur |
|-----------|--------|
| **Vitesse de croissance** | x3 plus rapide (besoins en nourriture /3) |
| **Bonus de production militaire** | +200% (x3 total) |
| **Influence de liberation** | 500 (vanilla : ~90) |
| **Influence de conversion en CE** | 500 |

---

## 6. Diplomatie et IA

### 6.0 — Systeme de Vassalite

Quand une civilisation est suffisamment affaiblie, le conquerant peut choisir de la **vassaliser** au lieu de l'annexer. La civilisation vaincue recupere la ville mais devient vassale.

**Mise en place :**
- L'option apparait dans le popup de conquete quand **toutes les conditions sont remplies** :
  - La civilisation vaincue a **3 villes ou moins**
  - La ville conquise **est la capitale originale**, OU la civilisation vaincue **ne possede plus sa capitale originale** (deja capturee)
  - La civilisation vaincue est une **civilisation majeure** (pas une cite-etat)
  - Le conquerant **n'est pas deja un vassal** lui-meme
- Un vassal ne peut pas creer d'autres vassaux
- La ville est rendue avec tous les effets de conquete (pillage, perte de population, resistance)
- La paix est etablie entre suzerain et vassal avec un traite de paix

**Restrictions diplomatiques :**
| Regle | Effet |
|-------|-------|
| **Declaration de guerre** | Les vassaux ne peuvent pas declarer la guerre independamment |
| **Protection contre la guerre** | Personne ne peut declarer la guerre a un vassal directement (il faut cibler le suzerain) |
| **Suivi en guerre** | Les vassaux rejoignent automatiquement toutes les guerres du suzerain |
| **Suivi en paix** | Les vassaux font automatiquement la paix quand le suzerain la fait |

**Tribut economique (25%) :**
| Ressource | Montant | Condition |
|-----------|---------|-----------|
| **Or** | 25% du revenu net d'or du vassal | Uniquement si revenu > 0 |
| **Science** | 25% du revenu net de science du vassal | Uniquement si revenu > 0 |

Le tribut est visible dans le detail des stats comme « Vassal tribute » (vassal) et « Vassal income » (suzerain).

**Guerre d'independance :**
- Necessite puissance militaire du vassal >= 25% de celle du suzerain
- Les vassaux IA declarent automatiquement l'independance quand la condition est remplie
- Les vassaux humains doivent attendre la condition (futur bouton UI)

**Echange de territoire :**
- Le suzerain peut echanger des tuiles avec son vassal via un ecran carte dedie (Diplomatie → Territory Exchange)
- Prendre des tuiles du vassal ou donner ses propres tuiles librement — aucun cout d'influence
- Contiguite imposee : impossible de laisser une ville deconnectee de ses tuiles

**Liberation automatique :**
- Si le suzerain est vaincu ou detruit, tous les vassaux sont automatiquement liberes

### 6.1 — ISI et influence sur les cites-etats

Quand l'ISI d'une civilisation majeure tombe sous 40, elle perd **5 influence par tour** avec chaque cite-etat (applique uniquement si influence > 0).

### 6.2 — Declaration de guerre de l'IA

- Les IA avec **ISI < 40** sur la cible gagnent jusqu'a **+10 de motivation** pour attaquer : `(40 - ISI_cible) / 4`
- Les IA **elles-memes instables** subissent un malus de motivation : `-5 x facteur de personnalite`
- Les civilisations malheureuses peuvent quand meme declarer la guerre si leur **ISI < 40** (guerres de desespoir)

---

## 7. Technologie

| Etat | Technologies militaires | Technologies civiles |
|------|-------------------------|----------------------|
| En guerre | **Cout /2** | **Cout x2** |
| En paix | **Cout x2** | Normal |

---

## 8. Reglage de difficulte IA

Un popup en jeu (Menu → **AI Bonuses**) fournit quatre curseurs pour ajuster les bonus des civilisations IA en temps reel :

| Stat | Plage | Defaut | Effet |
|------|-------|--------|-------|
| **Production** | 90% – 300% | 100% | Multiplie la production de toutes les villes IA |
| **Croissance** | 90% – 300% | 100% | Multiplie la nourriture de toutes les villes IA |
| **Or** | 90% – 300% | 100% | Multiplie l'or de toutes les villes IA |
| **Science** | 90% – 300% | 100% | Multiplie la science de toutes les villes IA |

- Pas de 5%
- Applique comme multiplicateur final sur les calculs de stats de toutes les villes non-humaines
- Sauvegarde dans le fichier de partie (persiste entre sauvegardes/chargements)
- Les changements prennent effet au tour suivant (ou immediatement a la fermeture pour toutes les villes IA)
- Se cumule avec les modificateurs de difficulte existants

---

---

# Expert Analysis: A Historian's Audit

## Imperial Overstretch & Systemic Realism

*Analysis by a military-economic historian and geopolitics specialist*

### What this mod gets right

**1. The fundamental rhythm of conquest-consolidation is historically accurate.** Every great empire — Rome, the Mongols, the Ottomans, Napoleonic France — went through cycles of rapid expansion followed by periods where the center had to digest its conquests. The ISI system captures this with the over-expansion penalty and the consolidation phase.

**2. Supply lines matter.** The distance-based unit maintenance forces players to think about logistics, which Clausewitz identified as the decisive factor in extended campaigns. Napoleon didn't lose in Russia because of battles — he lost because his supply lines stretched across 2,400 km of hostile territory.

**3. Territory is not free.** In vanilla Civ, borders expand passively and conquered land is instantly yours. This mod correctly models that territory must be *projected upon* (units claiming tiles) and *held* (encirclement, resistance). This mirrors real territorial control: a flag on a map means nothing without boots on the ground.

**4. City-states as buffer zones.** The ability to convert conquered cities into allied city-states mirrors historical vassal states and buffer kingdoms (the Roman client kingdoms, British protectorates, Soviet satellite states). This is an elegant strategic choice.

**5. War experience as institutional memory.** The gradual +1%/turn combat bonus during war reflects how armies professionalize during extended conflicts — the Roman legions after the Punic Wars, or the French Grande Armee after years of Revolutionary Wars.

### What could be improved — Recommendations

---

#### R1. ETHNIC/CULTURAL RESISTANCE (Nationalism Factor)

**Problem:** Currently, a city conquered from Egypt 100 turns ago and a city conquered 5 turns ago have the same "foreign city" penalty (-1). In reality, conquered populations assimilate over generations — or they don't, and nationalism festers.

**Recommendation:** Replace the binary "foreign city" factor with a **cultural assimilation timer**:
- Conquered cities start at 100% "foreign identity"
- Decays at ~2% per turn (50 turns for full assimilation)
- **Accelerated by:** shared religion (-1%), court building (-1%), connected to capital (-0.5%)
- **Slowed by:** different religion (+0.5%), recent conquest nearby (+1%)
- ISI penalty scales with foreign identity: `-0.03 × foreignIdentity` per city
- Revolts preferentially target cities with high foreign identity

This models the difference between Alsace (contested for centuries) and Normandy (fully French within 200 years).

---

#### R2. CIVIL WAR MECHANICS (Beyond City Revolts)

**Problem:** Currently, instability only produces isolated city-state secessions. In history, imperial collapse more often produces *rival claimants* (Roman Empire splits, Chinese warlord periods, successor kingdoms of Alexander) rather than independent city-states.

**Recommendation:** When ISI hits Collapse and 3+ cities are eligible for revolt:
- Instead of individual city-state conversions, trigger a **Civil War event**
- The empire splits into 2 factions: loyalists (capital + connected cities) vs. rebels (most distant/foreign cluster)
- The rebel faction becomes a **new major civilization** with its own AI, inheriting the territory and units
- Rebel faction starts at war with the loyalists
- Both factions inherit proportional tech, gold, and diplomatic relations

This creates dramatic, historically authentic moments like the fall of Rome or the breakup of the Mongol Empire.

---

#### R3. WAR WEARINESS (Population Factor)

**Problem:** The ISI accounts for military losses but not for the *cumulative psychological cost* of prolonged war. World War I didn't collapse empires because of single battles — it was 4 years of relentless grinding that broke the Russian, Ottoman, Austro-Hungarian, and German empires.

**Recommendation:** Add a **War Weariness** factor to ISI:
- `warWeariness` counter starts at 0
- At war: +1 per turn, +3 per unit lost, +10 per city lost
- At peace: -2 per turn
- ISI penalty: `-warWeariness / 5` (so 50 weariness = -10 ISI)
- War weariness also reduces food production by 1% per 10 points (population refuses to work)
- Peace treaties could include a "war reparations" clause that accelerates weariness decay

---

#### R4. ECONOMIC INTERDEPENDENCE (Trade Dependency)

**Problem:** There is no economic consequence to going to war with a trade partner. In reality, the economic disruption of cutting trade routes was often the strongest deterrent to war (and the strongest weapon — see the Continental System, modern sanctions).

**Recommendation:**
- Track **trade dependency** between civilizations based on trade route connections and treaties
- Declaring war on a trade partner: immediate gold penalty (% of shared trade income × 10 turns)
- Economic sanctions: allow declaring trade embargo without full war
- Blockade mechanics: military units on trade route tiles disrupt income for both sides
- ISI bonus for diverse trade partners (+1 per trade partner, up to +5)

---

#### R5. LEGITIMACY & GOVERNMENT TRANSITION

**Problem:** The ISI treats all governments equally. In reality, the *type* of governance profoundly affects stability. Democracies are slow to mobilize but resilient; autocracies are agile but brittle. The Athenian democracy survived repeated invasions; the Persian Empire collapsed from a single decisive defeat.

**Recommendation:** Add a **Legitimacy** modifier to ISI based on policy tree:
- **Tradition:** +5 ISI base, but -2 per city beyond 6 (small empires stabilize)
- **Liberty:** +1 ISI per city, but -3 if any city is in resistance (popular will)
- **Autocracy:** +10 ISI while at war, -10 ISI while at peace (military regimes stagnate)
- **Commerce:** +1 ISI per trade partner, -5 if deficit (prosperity-dependent)
- **Piety:** +3 if unified religion, -5 if religious diversity (theocratic rigidity)

---

#### R6. PLAGUE & FAMINE (Demographic Shocks)

**Problem:** Population grows monotonically. In reality, empires were periodically devastated by plagues (Black Death, Justinian Plague) and famines that reset demographic trajectories and often triggered political collapses.

**Recommendation:** At ISI < 30, introduce a small chance (2%/turn) of:
- **Famine:** -25% population in the weakest city, spreads to adjacent cities at 10% chance
- **Plague:** -10% population in ALL cities connected by roads (trade routes spread disease)
- Recovery bonus: surviving cities get +50% growth for 10 turns

This creates dramatic population crashes followed by recovery booms — matching the post-Black Death economic revolution.

---

#### R7. SEASONAL/TERRAIN ATTRITION (Campaign Limits)

**Problem:** Unit isolation attrition is binary (all 6 tiles enemy = 50 HP). Real campaigns fail gradually — the deeper you push, the more you bleed. Winter, mountains, deserts, and jungles should actively resist occupation.

**Recommendation:**
- **Graduated attrition:** Instead of binary, scale damage by % of enemy-owned adjacent tiles: `HP_loss = adjacentEnemyTiles × 8` (max 48 HP for all 6)
- **Terrain attrition:** Units ending turn in desert/tundra/snow without friendly tile within 3 hexes: -10 HP/turn
- **Campaign duration:** Military units track turns since leaving friendly territory. After 10 turns: -5% combat, after 20 turns: -10% combat + 10 HP attrition/turn
- Offset by: supply unit class (new unit type that extends campaign range), forts, roads

---

#### R8. DYNAMIC GOLDEN AGE (Earned, Not Automatic)

**Problem:** The Golden Age tier (ISI 80+) activates automatically whenever stability is high enough. Real golden ages — the Pax Romana, the Islamic Golden Age, the Tang Dynasty — were driven by specific conditions: cultural patronage, scientific investment, trade prosperity, AND stability.

**Recommendation:** Require **3 of 5 conditions** simultaneously for Golden Age:
1. ISI >= 80
2. Positive gold income AND treasury > 500
3. At peace for 10+ consecutive turns
4. Culture output in top 3 civilizations
5. A Wonder completed in the last 20 turns

This makes golden ages something players actively pursue rather than a passive bonus for not expanding.

---

### Synthesis

The mod already captures the essential tension of empire-building: **expansion creates instability, and instability limits expansion**. The recommendations above would deepen this into a more layered simulation where cultural identity, war weariness, economic interdependence, and governance philosophy all interact.

The most impactful additions would be **R1 (Cultural Assimilation)**, **R3 (War Weariness)**, and **R2 (Civil War)**. Together, they would transform the ISI from a single-axis stability meter into a multi-dimensional portrait of imperial health — where every conquest plants the seeds of either lasting integration or eventual fracture.

---

*611 tests passing. 20+ files modified. 40+ interlocking game mechanics.*
