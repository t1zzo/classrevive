# ClassRevive

**Retrouvez du Java lisible à partir de code compilé, et mesurez quelle part en est juste.**

[English](README.md) | [Português (Brasil)](README.pt-BR.md) | [Español](README.es.md) | [Italiano](README.it.md) | Français

![L'application de bureau ClassRevive affichant une classe Gson décompilée : l'arborescence des paquets à gauche, le code retenu avec une pastille verte d'aller-retour à côté de chaque méthode au centre, et le niveau de confiance et les preuves de la classe à droite](docs/images/desktop-class-dark.png)

ClassRevive exécute quatre décompilateurs Java reconnus (CFR, Vineflower, Procyon et JADX) sur un `.class`, un `.jar`, un APK Android ou un exécutable contenant un jar. Il recompile chaque résultat avec javac, le compare au bytecode d'origine et retient, fichier par fichier et méthode par méthode, la version que les preuves soutiennent. Chaque classe reçoit un niveau de confiance que vous pouvez vérifier, dans une application de bureau ou en ligne de commande.

> La documentation de référence est en anglais ; ce README est traduit. En cas de différence, la version anglaise fait foi.

## Ce qu'il fait

- **Décompile avec quatre moteurs et choisit par fichier et par méthode.** Chaque moteur s'exécute dans sa propre JVM. Les candidats sont classés selon qu'ils se recompilent ou non, le nombre de signatures structurelles qui correspondent à l'original et le nombre de corps de méthode qui reviennent identiques ou équivalents après recompilation.
- **Vérifie au lieu de deviner.** Chaque résultat indique ce qui a été mesuré : recompilation, signatures par classe, un aller-retour par méthode. Les résultats partiels conservent un listing lisible du bytecode de ce qui n'a pas pu être récupéré.
- **Gère les entrées difficiles.** Jars multi-release, noms de classe qui ne diffèrent que par la casse, exécutables qui embarquent un jar, ensembles de jars, fichiers APK et dex Android (convertis avec dex2jar).
- **Détecte l'obfuscation et en défait une partie.** Un profil statique nomme les techniques et l'obfuscateur qu'elles désignent, avec un score et les raisons. Trois obfuscateurs open source (Skidfuscator, Aidsfuscator et Radon) ainsi que Zelix KlassMaster 27 sont inversés automatiquement, et les flags de prédicats opaques de Zelix KlassMaster dont on prouve qu'ils sont constants sont simplifiés.
- **N'exécute jamais le code qu'il analyse.** Les classes analysées ne sont jamais initialisées et leurs méthodes ne sont jamais appelées. Voir [Sécurité](#sécurité-et-usage-responsable) pour la limite exacte.

## Ce qu'il ne fait pas

- Il ne prouve pas que le programme récupéré se comporte comme l'original. Le comportement est toujours signalé comme `NOT_VERIFIED`.
- Il ne peut pas restituer ce que la compilation a supprimé : commentaires, mise en forme et souvent noms des variables locales.
- Il ne retrouve pas les noms d'origine supprimés par un obfuscateur. Vous pouvez fournir le fichier de correspondance de l'obfuscateur (ProGuard, R8, yGuard), ou demander des noms déduits (`--infer-names`), signalés comme des suppositions avec leurs preuves.
- La recompilation s'effectue avec le JDK 21 : les fichiers de classe compilés pour Java 22 ou une version ultérieure sont lus et listés, mais pas vérifiés.
- Ce n'est ni un antivirus ni un bac à sable. Ses JVM auxiliaires isolent les pannes, pas le code hostile.
- Il ne décompile pas le code natif en Java. Les bibliothèques natives embarquées peuvent faire l'objet d'un premier examen, éventuellement avec un Ghidra que vous installez vous-même.

## Démarrage rapide

**Prérequis :** un **JDK** Java 21 (pas seulement un JRE), car ClassRevive utilise javac pour vérifier ses résultats. [Eclipse Temurin 21](https://adoptium.net) fonctionne sous Windows, Linux et macOS. `java -version` doit afficher 21.

1. Téléchargez le zip de la dernière version depuis la [page Releases](https://github.com/t1zzo/classrevive/releases) et décompressez-le où vous voulez.
2. Ouvrez l'application de bureau :

   ```bash
   bin/classrevive-gui
   ```

   Sous Windows, double-cliquez sur `bin\classrevive-gui.bat`. Ouvrez ou déposez un `.jar`, appuyez sur **F5** pour décompiler et lisez chaque classe avec son niveau de confiance.
3. Ou utilisez la ligne de commande :

   ```bash
   bin/classrevive decompile app.jar --engines all
   ```

   Cela crée un nouveau dossier `app-classrevive/` : le code retenu dans `selection/sources/`, une carte de confiance dans `report.html`, un projet pour IDE dans `project/` et la sortie brute de chaque moteur à côté. Les dossiers existants ne sont jamais écrasés.

`classrevive --help` liste toutes les commandes et `classrevive help decompile` en explique une. Pour compiler à partir des sources, lancez `./gradlew :cli:installDist` avec le JDK 21 ; les lanceurs se trouvent dans `modules/cli/build/install/classrevive/bin/`.

Vous débutez en décompilation ? Lisez [Getting started](docs/getting-started.md) (en anglais), ou le [guia para iniciantes](docs/guia-iniciante.md) en portugais.

## Fonctionnalités

| Domaine | Ce que vous obtenez | Documentation |
|---|---|---|
| Application de bureau | Explorateur de paquets et de classes avec filtre de confiance, bytecode avant toute décompilation, progression en direct avec annulation, un verdict en langage clair, confiance par classe et marques d'aller-retour par méthode, erreurs de javac soulignées, comparaison des moteurs côte à côte, recherche des utilisations et hiérarchie des appels, aller à une classe, recherche dans le code, export en projet pour IDE, thèmes sombre et clair, anglais et portugais | [Getting started](docs/getting-started.md#4-the-desktop-application) |
| Sélection vérifiée | Recompilation, signatures structurelles, aller-retour par méthode, hybrides bornés, réparations du code guidées par le compilateur, composition par méthode entre moteurs | [Architecture](ARCHITECTURE.md#selection-and-composition) |
| Entrées | `.class`, `.jar`, jars multi-release, exécutables contenant un jar, ensembles de jars, APK et dex Android | [Référence de la CLI](docs/cli-reference.md#executables-bundles-and-android-inputs) |
| Bibliothèques manquantes | Fournissez vos propres jars avec `--classpath` ; sinon, des substituts déduits permettent au résultat de compiler, et celui-ci est signalé comme reposant sur des stubs | [Référence de la CLI](docs/cli-reference.md#selection-evidence-and-limits) |
| Obfuscation | Profil statique des techniques et hypothèses d'obfuscateur avec scores ; inversion de Skidfuscator, Aidsfuscator, Radon et Zelix KlassMaster 27 ; une passe générale de simplification du flot de contrôle ; simplification des flags de prédicats opaques ; passes optionnelles de décodage des constantes et des chaînes | [Détection de l'obfuscation](docs/obfuscation-detection.md) |
| Renommage | Alias lisibles, fichiers de correspondance explicites, une correspondance ProGuard/R8 ou un journal yGuard pour restaurer les noms d'origine lorsque c'est sûr, et des noms déduits optionnels signalés avec leurs preuves | [Référence de la CLI](docs/cli-reference.md#rename-experimental) |
| Diagnostics | Indices statiques de réflexion, de chargement de classes, d'invokedynamic et de cryptographie ; empreinte du compilateur ; reconnaissance des bibliothèques embarquées ; premier examen optionnel des bibliothèques natives avec Ghidra | [Référence de la CLI](docs/cli-reference.md#diagnose) |
| Rapports | `report.txt`, `report.json` (schéma v1), une carte de confiance autonome `report.html`, export en projet pour IDE | [Format des rapports](docs/reports.md) |
| Mesure | Commande de benchmark, un tableau des scores sur des bibliothèques de Maven Central vérifiées par hash, et un laboratoire des échecs qui transforme les échecs en tests de non-régression | [Benchmarks](docs/benchmarks.md) |

## Comment ça marche

```text
entrée (.class / .jar / .apk / .exe)
  │  prise en charge bornée : lue comme des données, jamais extraite ni exécutée
  ▼
normalisation ── dex2jar pour Android · noms portables · version de base des jars multi-release
  │
  ▼
diagnostic ── profil d'obfuscation ; inverse un obfuscateur reconnu (contrôlé par le vérificateur de la JVM)
  │
  ▼
CFR · Vineflower · Procyon · JADX        chacun dans sa propre JVM auxiliaire
  │
  ▼
javac dans une JVM auxiliaire ── signatures par classe · aller-retour par méthode
  │
  ▼
sélection ── classement · hybrides · réparations du code · composition par méthode
  │
  ▼
code retenu + report.html / report.json + projet pour IDE (+ bytecode de secours)
```

Les détails, y compris ce que chaque étape refuse de faire, se trouvent dans [ARCHITECTURE.md](ARCHITECTURE.md) (en anglais).

## Benchmarks

Le tableau des scores décompile de vraies bibliothèques open source téléchargées depuis Maven Central et vérifiées par SHA-256. Aucune règle de ClassRevive n'a été ajustée sur les deux ensembles de validation (*holdout*). Mesuré le 29/10/2026 avec ClassRevive 0.1.0-dev, avec les quatre moteurs plus la sélection combinée, sous Windows avec le JDK 21 :

| Ensemble | Bibliothèques | Compilent en entier | Signatures | Corps, bibliothèque entière | Corps, avec crédit par fichier |
|---|---:|---:|---:|---:|---:|
| `holdout` | 3 | 3/3 | 2 372/2 375 (99,8 %) | 1 752/1 765 (99,2 %) | 1 752/1 765 (99,2 %) |
| `holdout2` | 13 | 5/13 | 21 583/52 080 (41,4 %) | 8 774/38 445 (22,8 %) | 35 899/38 445 (93,3 %) |

- **Compilent en entier** : le code retenu de toute la bibliothèque compile avec javac par rapport aux dépendances qu'elle déclare. Sur `holdout2`, il reste encore de 1 à 33 erreurs javac à 8 bibliothèques sur 13.
- **Signatures** : classes, champs et méthodes dont la structure correspond à l'original, comptés sur les classes émises par javac ; une bibliothèque qui ne compile pas y contribue peu.
- **Corps, bibliothèque entière** : corps de méthode identiques ou équivalents à l'original après recompilation, comptés uniquement pour les bibliothèques qui compilent en entier. Le dénominateur est l'ensemble des corps de méthode de l'original.
- **Corps, avec crédit par fichier** : même chose, mais chaque fichier d'une bibliothèque qui ne compile pas est aussi compilé seul par rapport aux classes d'origine, et ses corps comptent lorsque ce fichier compile. Cela mesure quelle part du code est juste fichier par fichier, pas un programme que vous pouvez reconstruire.

Aucun de ces chiffres ne mesure le comportement, et ils ne disent rien de certain sur d'autres entrées, en particulier les entrées obfusquées.

Reproduisez-les avec une copie du dépôt et le JDK 21 :

```bash
./gradlew :cli:scoreboard -PscoreboardSet=holdout
./gradlew :cli:scoreboard -PscoreboardSet=holdout2
```

Chaque exécution écrit `build/scoreboard/<set>-<timestamp>/benchmark.md`. Les chiffres par bibliothèque, l'ensemble `dev` et la façon de lire chaque colonne se trouvent dans [docs/benchmarks.md](docs/benchmarks.md) (en anglais).

## Sécurité et usage responsable

ClassRevive n'exécute jamais le code qu'il analyse : aucune classe analysée n'est initialisée et aucune méthode analysée n'est appelée. Le bytecode est lu comme des données, et certaines passes évaluent du code constant pur dans un interpréteur statique borné. Après la réécriture du bytecode (par exemple lors de l'inversion d'un obfuscateur), les classes réécrites sont liées, jamais initialisées, dans une JVM auxiliaire séparée afin que le vérificateur de la JVM puisse les contrôler. Les décompilateurs, javac et les autres étapes lourdes s'exécutent dans des JVM auxiliaires séparées, avec des limites de temps, de mémoire et de sortie. Il s'agit d'une isolation des pannes, **pas d'un bac à sable du système d'exploitation** : analysez les fichiers délibérément hostiles dans une machine virtuelle.

N'analysez que des logiciels que vous avez le droit d'analyser : votre propre code, du code open source, ou des cas que votre licence ou la loi autorisent. Lisez l'[avertissement](DISCLAIMER.md) avant d'utiliser ClassRevive sur des logiciels tiers, et consultez [SECURITY.md](SECURITY.md) pour signaler une vulnérabilité. Merci de ne pas joindre de binaires privés ou propriétaires aux issues publiques.

Les noms d'obfuscateurs et de produits (Zelix KlassMaster, Allatori, DashO, Stringer, ProGuard, R8, Skidfuscator, Aidsfuscator et autres) appartiennent à leurs propriétaires. ClassRevive n'est affilié à aucun d'entre eux, et sa détection est heuristique.

## Documentation

- [Getting started](docs/getting-started.md) · [Guia para iniciantes (portugais)](docs/guia-iniciante.md)
- [Référence de la ligne de commande](docs/cli-reference.md)
- [Architecture](ARCHITECTURE.md) et [état : ce qui fonctionne et ce qui reste à faire](docs/status.md)
- [Format des rapports](docs/reports.md) · [Détection de l'obfuscation](docs/obfuscation-detection.md) · [Simplification du flot de contrôle](docs/control-flow.md) · [Radon et yGuard](docs/radon-yguard.md) · [Zelix KlassMaster 27](docs/zelix-klassmaster.md) · [Noms déduits](docs/inferred-names.md) · [Diagnostics de liaison dynamique](docs/dynamic-diagnostics.md)
- [Benchmarks](docs/benchmarks.md) · [Journal des modifications](CHANGELOG.md)
- Expérimental : [API des passes](docs/pass-sdk.md), [bac à sable](docs/sandbox.md), [protocole de comportement](docs/behavior-protocol.md)


## Contribuer

Les rapports de bogue, les cas de test et les pull requests sont les bienvenus ; voir [CONTRIBUTING.md](CONTRIBUTING.md). La vérification complète est `./gradlew test :cli:installDist :cli:distZip`. Un cas d'échec réduit à partir de votre propre code ou de code sous licence libre, via le [laboratoire des échecs](docs/cli-reference.md#failure-lab), est le rapport de bogue le plus utile.

## Licence

ClassRevive est distribué sous la [ClassRevive License 1.0](LICENSE) : utilisation libre, y compris commerciale, modification et redistribution avec mention de l'origine ; les forks ne peuvent pas utiliser le nom ni l'identité visuelle de ClassRevive. La distribution inclut CFR (MIT), Vineflower, Procyon et JADX (Apache-2.0), ASM (BSD-3-Clause), FlatLaf (Apache-2.0), RSyntaxTextArea (BSD-3-Clause), dex2jar (Apache-2.0) et d'autres bibliothèques sous leurs propres licences ; voir [NOTICE](NOTICE), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) et [third-party-licenses/](third-party-licenses/).
