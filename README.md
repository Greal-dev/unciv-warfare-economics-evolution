# Territorial Warfare — Total Conversion of Unciv

*A fork of [Unciv](https://github.com/yairm210/Unciv) (open-source Civ V remake)*

---

## [English Version](#english) | [Version Francaise](#francais)

---

<a name="english"></a>
## English

### Why this mod exists

In standard Civilization, borders are lines on a map. You conquer a city, and it is yours — immediately, completely, without question. The population complies. The economy adapts. History tells a very different story.

The Roman Empire did not fall because an army marched on Rome. It fell because its provinces had become culturally autonomous, its roads no longer carried imperial authority, and the populations it claimed to govern had long ceased to identify as Roman. The British Empire did not lose India in 1947; it had been losing India for decades, as local identities proved more resilient than colonial administration.

**Territorial Warfare** rebuilds Unciv from the ground up around this insight: **territory is not a line on a map — it is a demographic reality.**

---

### Ethnocultural Demographics

Every tile tracks its **cultural composition** — which populations actually live there, expressed as percentages summing to 100%. A tile might be 60% Egyptian, 25% Babylonian, 15% barbarian. This composition evolves every turn through city influence, military garrisons, improvements, neighbor diffusion, and natural assimilation. All civilizations compete for demographic influence simultaneously.

In standard Civ, conquering a city makes it yours. Here, conquering a city gives you a city full of people who are not your people. They produce less. They innovate less. And if you cannot assimilate them, they will eventually revolt.

**Productivity** is scaled by cultural homogeneity: 100% at 80%+ friendly culture, linearly decreasing below that. A freshly conquered city with 40% friendly culture produces half its potential. **Science** suffers even more acutely — fragmented cities cannot sustain research institutions.

This is not a penalty. This is what actually happens when empires overextend.

### Local Identity and National Unity

Every city develops its own cultural identity — "Lyon" is not "France" until the roads connect them.

A city **not connected** to the capital by road or port projects its **local culture**. It remains loyal to the empire but does not carry imperial identity into the surrounding territory. When roads or ports establish the connection, local culture gradually converts to national culture (5% per turn) — the city becomes culturally integrated.

This creates the historical dynamic where **infrastructure builds nations**. The Roman road network was not merely logistical — it was the mechanism of Romanization. Cut the roads, and the provinces revert to local identities. They don't rebel — they simply stop being Roman.

Yield bonuses count the **sum of all friendly cultures** (national + local cities of the same civ). Lyon at 60% Lyonnaise + 20% French = 80% = full productivity.

### Natural Frontiers

Mountains and coasts are **cultural barriers** — they receive influence but never project it. The Pyrenees between France and Spain, the English Channel, the Alps — these are not arbitrary lines but demographic realities. Culture does not flow through mountain passes at the same rate it flows across plains.

### Rebellion and Secession

When foreign culture exceeds 70% on a tile, **rebellion** erupts. A military garrison can hold the rebellion, but at a cost: 15 HP attrition per turn. Without a garrison, the tile secedes after 3 turns.

Secession is not random. The tile transfers to the **dominant foreign culture's civilization** — or, if barbarian decay is high enough (owner below 40%, barbarians above 25%), the tile becomes neutral or the city becomes an independent **city-state**. This is how the Western Roman Empire fragmented: not through conquest, but through the emergence of autonomous polities from within.

### Military Encirclement

If your military cuts enemy territory off from their cities — verified through pathfinding — the isolated tiles are conquered and stranded land units take 50 HP/turn attrition. Armies without supply lines do not simply stand there. They starve.

---

### The Weight of Famine

There is no food stockpile. When a city cannot feed its population, it loses 1 inhabitant every 2 turns. Famines are immediate and devastating — as they were throughout most of human history. The Irish Potato Famine, the Bengal famines, the Great Leap Forward — populations collapse when food systems fail, and they do not recover by drawing from a magical granary reserve.

Conversely, well-fed cities grow at 2x normal speed. Demographic booms are possible — but fragile.

Workers and settlers cost **2 population each**. Founding a colony is not free. It is a demographic investment, as it was when European powers shipped their populations across oceans.

---

### The Industrial Revolution

From the Industrial era, production follows an **exponential growth curve**: +2% per turn at the start, halving every 100 turns, with an asymptotic cap around x3.9. This is the hockey stick of industrialization — the transition from artisanal production to mechanized output that took Britain a century and transformed global power dynamics.

**Production smoothing** ensures that economic development has **inertia**. Building a factory does not instantly double output. The benefit materializes over ~30 turns — training workers, establishing supply chains, adapting processes. Recovering from an economic crisis (-20%) takes ~10 turns. Economies do not bounce back overnight. Ask any historian of the Great Depression.

### Improvement Maturation

A newly built farm or mine produces only **50%** of its potential yield. Output scales logarithmically to 100% at ~200 turns and up to ~150% beyond. Pillaged-then-repaired improvements restart at 75% maturation.

This is the economic logic of stability. A Roman province that has been farmed for two centuries is vastly more productive than a newly cleared frontier. Institutions, knowledge, infrastructure — they compound over time. War destroys this capital, and it does not rebuild in a season.

---

### Systemic Collapse

Three historical crises periodically shake the world:

| Crisis | Year | Duration | Historical parallel |
|--------|------|----------|-------------------|
| Bronze Age Collapse | 1200 BC | 10 turns | Sea Peoples, fall of Mycenae, Hittite collapse |
| Fall of Rome | 450 AD | 20 turns | Migration Period, fragmentation of imperial authority |
| Late Modern Collapse | 2100 AD | 20 turns | Climate crisis, institutional breakdown |

During crises, barbarian pressure intensifies everywhere. Conquered cities amplify the pressure (+2% per city in a 3-tile radius). Founding civilizations receive cultural pressure on their lost cities (+10% on capitals, +5% on others) — the memory of independence does not die easily.

These are not random events. They are **systemic emergences** — moments when the accumulated contradictions of imperial overextension become unsustainable.

### Colonial Expansion and Decolonization

From the **Renaissance**, settlers cost **10x less** to produce. This enables the rapid colonial expansion that characterized the 15th-18th centuries — when European powers established global empires at relatively low demographic cost.

From the **Modern era**, the pendulum swings. Overseas colonies (15+ tiles from capital, not connected by land) face **decolonization pressure**: +10% barbarian culture per turn on city centers, +5% on surrounding tiles. Within 6-10 turns, colonial control erodes and cities secede as independent city-states. The European colonial empires did not last because they could not — the demographic mathematics of governing distant populations without cultural integration are ultimately unsustainable.

**DOM-TOM mechanic**: colonies *can* be held through massive military presence. Each military unit in a city's territory adds +2% pacification per turn (up to 20% with 8+ units). France's overseas territories persist precisely because of sustained military and institutional investment — but at enormous ongoing cost.

---

### Science and Knowledge Diffusion

**Border porosity**: tiles adjacent to a more advanced civilization generate science proportional to the **tech gap**. Knowledge does not respect borders. The Islamic Golden Age transmitted Greek philosophy to medieval Europe. Japanese industrialization accelerated through proximity to Western trade. Isolation breeds stagnation; contact breeds innovation.

**Tech maintenance**: civilizations far ahead of the average pay a **superlinear penalty** (quadratic scaling). Maintaining a technological edge is expensive. Research institutions, education systems, infrastructure — the cost of being at the frontier increases faster than the benefits.

**War and peace**: military technologies cost 2x in peacetime (no urgency to innovate). Civilian technologies cost 2x during war (resources diverted to the war effort). The Manhattan Project was only possible because of total war mobilization.

**Gold-to-science**: allocating treasury to research gives a **percentage bonus** (100% = x2 science) while reducing gold income proportionally. This is not alchemy — it is investment.

### Economy

- **Tile tax**: +0.5 gold per tile, scaled by cultural productivity. Diverse populations pay less tax because the administrative apparatus is less effective.
- **Unit maintenance**: +1 gold per military unit per era. A modern army costs more to maintain than a Bronze Age militia.
- **Road upkeep**: free. Infrastructure investment should not be penalized.
- **Scorched earth**: units can pillage their own territory when enemies are within 2 tiles, healing 30 HP. Denial of resources to an advancing enemy is a legitimate — if desperate — tactic.
- **Small nation defense**: 1 city = +100% defense, 2 = +50%, 3 = +25%. Thermopylae was not an accident. Small nations fight harder when survival is at stake.

### Barbarian Dynamics

Barbarians are not random annoyances. They are the **demographic substrate** — the populations that exist outside state structures. Unowned tiles start at 100% barbarian culture. When a civilization's grip weakens (barbarian culture above 50%), barbarian units spawn spontaneously. This is how the Migration Period worked: the "barbarians" were always there. They simply became visible when imperial authority receded.

Captured settlers are not destroyed. Barbarians keep them and **seek a suitable location to found a city-state** — crossing oceans if necessary. This is how city-states historically emerged: migrating populations finding and settling unclaimed territory.

### Age of Discovery (Renaissance+)

Maritime exploration reveals **10 coast tiles per turn**. Land exploration spreads from explored tiles to their neighbors every 2 turns. The sea was always faster than the land — the Portuguese reached India before Europe had fully mapped its own interior.

---

*Based on [Unciv](https://github.com/yairm210/Unciv) by yairm210.*

---

<a name="francais"></a>
## Francais

### Pourquoi ce mod existe

Dans Civilization standard, les frontieres sont des lignes sur une carte. Vous conquerez une ville, et elle est a vous — immediatement, completement, sans discussion. La population obeit. L'economie s'adapte. L'histoire raconte une tout autre chose.

L'Empire romain n'est pas tombe parce qu'une armee a marche sur Rome. Il est tombe parce que ses provinces etaient devenues culturellement autonomes, que ses routes ne portaient plus l'autorite imperiale, et que les populations qu'il pretendait gouverner avaient depuis longtemps cesse de s'identifier comme romaines. L'Empire britannique n'a pas perdu l'Inde en 1947 ; il perdait l'Inde depuis des decennies, les identites locales s'averant plus resilientes que l'administration coloniale.

**Territorial Warfare** reconstruit Unciv depuis zero autour de cette intuition : **le territoire n'est pas une ligne sur une carte — c'est une realite demographique.**

---

### Demographie ethnoculturelle

Chaque case suit sa **composition culturelle** — quelles populations y vivent reellement, exprimees en pourcentages totalisant 100%. Une case peut etre 60% egyptienne, 25% babylonienne, 15% barbare. Cette composition evolue a chaque tour par l'influence des villes, les garnisons militaires, les amenagements, la diffusion entre voisins et l'assimilation naturelle. Toutes les civilisations rivalisent simultanement pour l'influence demographique.

Dans Civ standard, conquerir une ville la rend votre. Ici, conquerir une ville vous donne une ville pleine de gens qui ne sont pas les votres. Ils produisent moins. Ils innovent moins. Et si vous ne parvenez pas a les assimiler, ils finiront par se revolter.

La **productivite** est proportionnelle a l'homogeneite culturelle : 100% a partir de 80% de culture amie, decroissance lineaire en dessous. Une ville fraichement conquise a 40% de culture amie ne produit que la moitie de son potentiel. La **science** souffre encore plus — les villes fragmentees ne peuvent pas soutenir d'institutions de recherche.

Ce n'est pas une penalite. C'est ce qui arrive reellement quand les empires s'etendent trop.

### Identite locale et unite nationale

Chaque ville developpe sa propre identite culturelle — "Lyon" n'est pas "la France" tant que les routes ne les connectent pas.

Une ville **non connectee** a la capitale par route ou port projette sa **culture locale**. Elle reste loyale a l'empire mais ne porte pas l'identite imperiale dans le territoire environnant. Quand les routes ou les ports etablissent la connexion, la culture locale se convertit progressivement en culture nationale (5% par tour) — la ville s'integre culturellement.

Cela cree la dynamique historique ou **l'infrastructure construit les nations**. Le reseau routier romain n'etait pas seulement logistique — c'etait le mecanisme de la romanisation. Coupez les routes, et les provinces reviennent a leurs identites locales. Elles ne se rebellent pas — elles cessent simplement d'etre romaines.

Les bonus de rendement comptent la **somme de toutes les cultures amies** (nationale + villes locales de la meme civilisation). Lyon a 60% lyonnaise + 20% francaise = 80% = productivite complete.

### Frontieres naturelles

Les montagnes et les cotes sont des **barrieres culturelles** — elles recoivent l'influence mais ne la projettent jamais. Les Pyrenees entre la France et l'Espagne, la Manche, les Alpes — ce ne sont pas des lignes arbitraires mais des realites demographiques. La culture ne circule pas a travers les cols de montagne au meme rythme qu'a travers les plaines.

### Rebellion et secession

Quand la culture etrangere depasse 70% sur une case, la **rebellion** eclate. Une garnison militaire peut contenir la rebellion, mais au prix d'une attrition de 15 PV/tour. Sans garnison, la case fait secession apres 3 tours.

La secession n'est pas aleatoire. La case est transferee a la **civilisation de la culture etrangere dominante** — ou, si le declin barbare est suffisamment eleve (proprietaire sous 40%, barbares au-dessus de 25%), la case devient neutre ou la ville devient une **cite-Etat** independante. C'est ainsi que l'Empire romain d'Occident s'est fragmente : non par la conquete, mais par l'emergence de polities autonomes en son sein.

### Encerclement militaire

Si votre armee coupe le territoire ennemi de ses villes — verifie par calcul de chemin — les cases isolees sont conquises et les unites terrestres isolees subissent 50 PV/tour d'attrition. Les armees sans lignes d'approvisionnement ne restent pas simplement la. Elles meurent de faim.

---

### Le poids de la famine

Il n'y a pas de reserve de nourriture. Quand une ville ne peut pas nourrir sa population, elle perd 1 habitant tous les 2 tours. Les famines sont immediates et devastatrices — comme elles l'ont ete pendant la majeure partie de l'histoire humaine. La Grande Famine irlandaise, les famines du Bengale, le Grand Bond en avant — les populations s'effondrent quand les systemes alimentaires echouent, et elles ne se retablissent pas en puisant dans une reserve magique de grenier.

A l'inverse, les villes bien nourries croissent 2 fois plus vite. Les booms demographiques sont possibles — mais fragiles.

Les ouvriers et les colons coutent **2 habitants chacun**. Fonder une colonie n'est pas gratuit. C'est un investissement demographique, comme quand les puissances europeennes ont expédie leurs populations a travers les oceans.

---

### La Revolution industrielle

A partir de l'ere industrielle, la production suit une **courbe de croissance exponentielle** : +2%/tour au depart, divise par deux tous les 100 tours, avec un plafond asymptotique d'environ x3,9. C'est la courbe en crosse de hockey de l'industrialisation — la transition de la production artisanale a la production mecanisee qui a pris un siecle a la Grande-Bretagne et transforme les rapports de force mondiaux.

Le **lissage de production** garantit que le developpement economique a une **inertie**. Construire une usine ne double pas instantanement la production. Le benefice se materialise sur ~30 tours — former les ouvriers, etablir les chaines d'approvisionnement, adapter les processus. Se remettre d'une crise economique (-20%) prend ~10 tours. Les economies ne rebondissent pas du jour au lendemain. Demandez a n'importe quel historien de la Grande Depression.

### Maturation des amenagements

Une ferme ou une mine fraichement construite ne produit que **50%** de son rendement potentiel. La production evolue de facon logarithmique jusqu'a 100% au bout de ~200 tours et ~150% au-dela. Les amenagements pilles puis repares repartent a 75% de maturation.

C'est la logique economique de la stabilite. Une province romaine cultivee depuis deux siecles est infiniment plus productive qu'une frontiere fraichement defrichee. Les institutions, le savoir, les infrastructures — tout cela se compose avec le temps. La guerre detruit ce capital, et il ne se reconstruit pas en une saison.

---

### Effondrements systemiques

Trois crises historiques ebranlent periodiquement le monde :

| Crise | Annee | Duree | Parallele historique |
|-------|-------|-------|---------------------|
| Effondrement de l'Age du Bronze | 1200 av. J.-C. | 10 tours | Peuples de la Mer, chute de Mycenes, effondrement hittite |
| Chute de Rome | 450 ap. J.-C. | 20 tours | Grandes Migrations, fragmentation de l'autorite imperiale |
| Effondrement moderne | 2100 ap. J.-C. | 20 tours | Crise climatique, defaillance institutionnelle |

Pendant les crises, la pression barbare s'intensifie partout. Les villes conquises amplifient la pression (+2% par ville dans un rayon de 3 cases). Les civilisations fondatrices recoivent une pression culturelle sur leurs villes perdues (+10% sur les capitales, +5% sur les autres) — la memoire de l'independance ne meurt pas facilement.

Ce ne sont pas des evenements aleatoires. Ce sont des **emergences systemiques** — des moments ou les contradictions accumulees de la surextension imperiale deviennent insoutenables.

### Expansion coloniale et decolonisation

A partir de la **Renaissance**, les colons coutent **10 fois moins** a produire. Cela permet l'expansion coloniale rapide qui a caracterise les XVe-XVIIIe siecles — quand les puissances europeennes ont etabli des empires mondiaux a un cout demographique relativement faible.

A partir de l'**ere moderne**, le pendule oscille. Les colonies d'outre-mer (15+ cases de la capitale, non connectees par la terre) subissent une **pression de decolonisation** : +10% de culture barbare par tour sur les centres-villes, +5% sur les cases environnantes. En 6 a 10 tours, le controle colonial s'erode et les villes font secession en tant que cites-Etats independantes. Les empires coloniaux europeens n'ont pas dure parce qu'ils ne le pouvaient pas — les mathematiques demographiques de la gouvernance de populations lointaines sans integration culturelle sont fondamentalement insoutenables.

**Mecanisme DOM-TOM** : les colonies *peuvent* etre maintenues par une presence militaire massive. Chaque unite militaire sur le territoire d'une ville ajoute +2% de pacification par tour (jusqu'a 20% avec 8+ unites). Les territoires d'outre-mer de la France persistent precisement grace a un investissement militaire et institutionnel soutenu — mais a un cout permanent considerable.

---

### Science et diffusion des savoirs

**Porosite frontaliere** : les cases adjacentes a une civilisation plus avancee generent de la science proportionnellement a l'**ecart technologique**. Le savoir ne respecte pas les frontieres. L'Age d'or islamique a transmis la philosophie grecque a l'Europe medievale. L'industrialisation japonaise s'est acceleree par la proximite du commerce occidental. L'isolement engendre la stagnation ; le contact engendre l'innovation.

**Maintenance technologique** : les civilisations tres en avance sur la moyenne paient une **penalite superlineaire** (echelle quadratique). Maintenir une avance technologique est couteux. Institutions de recherche, systemes educatifs, infrastructures — le cout d'etre a la frontiere augmente plus vite que les benefices.

**Guerre et paix** : les technologies militaires coutent 2x en temps de paix (pas d'urgence a innover). Les technologies civiles coutent 2x en temps de guerre (ressources detournees vers l'effort de guerre). Le Projet Manhattan n'a ete possible que grace a la mobilisation de guerre totale.

**Or vers science** : allouer le tresor a la recherche donne un **bonus en pourcentage** (100% = x2 science) tout en reduisant les revenus en or proportionnellement. Ce n'est pas de l'alchimie — c'est de l'investissement.

### Economie

- **Taxe territoriale** : +0,5 or par case, proportionnel a la productivite culturelle. Les populations diversifiees paient moins d'impots car l'appareil administratif est moins efficace.
- **Entretien des unites** : +1 or par unite militaire par ere. Une armee moderne coute plus cher a entretenir qu'une milice de l'Age du Bronze.
- **Entretien des routes** : gratuit. L'investissement en infrastructure ne devrait pas etre penalise.
- **Terre brulee** : les unites peuvent piller leur propre territoire quand des ennemis sont a 2 cases, regagnant 30 PV. Le deni de ressources a un ennemi en progression est une tactique legitime — quoique desesperee.
- **Defense des petites nations** : 1 ville = +100% de defense, 2 = +50%, 3 = +25%. Les Thermopyles n'etaient pas un accident. Les petites nations se battent plus ferocenement quand la survie est en jeu.

### Dynamique barbare

Les barbares ne sont pas des nuisances aleatoires. Ils sont le **substrat demographique** — les populations existant en dehors des structures etatiques. Les cases non possedees commencent a 100% de culture barbare. Quand l'emprise d'une civilisation faiblit (culture barbare au-dessus de 50%), des unites barbares apparaissent spontanement. C'est ainsi que la Periode des Migrations a fonctionne : les "barbares" etaient toujours la. Ils sont simplement devenus visibles quand l'autorite imperiale a recule.

Les colons captures ne sont pas detruits. Les barbares les gardent et **cherchent un emplacement propice pour fonder une cite-Etat** — traversant les oceans si necessaire. C'est ainsi que les cites-Etats ont historiquement emerge : des populations migrantes trouvant et s'installant sur des territoires non revendiques.

### Age des Decouvertes (Renaissance+)

L'exploration maritime revele **10 cases cotieres par tour**. L'exploration terrestre se propage des cases explorees a leurs voisines tous les 2 tours. La mer a toujours ete plus rapide que la terre — les Portugais ont atteint l'Inde avant que l'Europe n'ait completement cartographie son propre interieur.

---

*Base sur [Unciv](https://github.com/yairm210/Unciv) par yairm210.*
