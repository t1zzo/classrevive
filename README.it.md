# ClassRevive

**Recupera Java leggibile dal codice compilato e misura quanto è corretto.**

[English](README.md) | [Português (Brasil)](README.pt-BR.md) | [Español](README.es.md) | Italiano | [Français](README.fr.md)

![L'applicazione desktop di ClassRevive che mostra una classe decompilata di Gson: l'albero dei pacchetti a sinistra, il codice selezionato con un pallino verde di andata e ritorno accanto a ogni metodo al centro, e il livello di affidabilità e le prove della classe a destra](docs/images/desktop-class-dark.png)

ClassRevive esegue quattro decompilatori Java noti (CFR, Vineflower, Procyon e JADX) su un `.class`, un `.jar`, un APK Android o un eseguibile con un jar incorporato. Ricompila ogni risultato con javac, lo confronta con il bytecode originale e conserva, file per file e metodo per metodo, la versione supportata dalle prove. Ogni classe ottiene un livello di affidabilità che puoi verificare, in un'applicazione desktop o dalla riga di comando.

> La documentazione di riferimento è in inglese; questo README è tradotto. In caso di differenze, fa fede la versione inglese.

## Cosa fa

- **Decompila con quattro motori e sceglie per file e per metodo.** Ogni motore gira nella propria JVM. I candidati vengono ordinati in base al fatto che ricompilino, a quante firme strutturali corrispondono all'originale e a quanti corpi di metodo risultano identici o equivalenti dopo la ricompilazione.
- **Verifica invece di tirare a indovinare.** Ogni risultato dice che cosa è stato misurato: ricompilazione, firme per classe, un'andata e ritorno per metodo. I risultati parziali conservano un elenco leggibile del bytecode di ciò che non è stato possibile recuperare.
- **Gestisce input difficili.** Jar multi-release, nomi di classe che collidono per maiuscole e minuscole, eseguibili che contengono un jar, raccolte di jar, file APK e dex di Android (convertiti con dex2jar).
- **Rileva l'offuscamento e ne annulla una parte.** Un profilo statico indica le tecniche e l'offuscatore a cui rimandano, con un punteggio e le motivazioni. Tre offuscatori open source (Skidfuscator, Aidsfuscator e Radon) e Zelix KlassMaster 27 vengono invertiti automaticamente, e i flag dei predicati opachi di Zelix KlassMaster dimostrabilmente costanti vengono semplificati.
- **Non esegue mai il codice che analizza.** Le classi analizzate non vengono mai inizializzate e i loro metodi non vengono mai invocati. Vedi [Sicurezza](#sicurezza-e-uso-responsabile) per il confine preciso.

## Cosa non fa

- Non dimostra che il programma recuperato si comporti come l'originale. Il comportamento viene sempre riportato come `NOT_VERIFIED`.
- Non può recuperare ciò che la compilazione ha scartato: commenti, formattazione e spesso i nomi delle variabili locali.
- Non recupera i nomi originali rimossi da un offuscatore. Puoi fornire il file di mappatura dell'offuscatore (ProGuard, R8, yGuard) oppure chiedere nomi dedotti (`--infer-names`), contrassegnati come ipotesi insieme alle relative prove.
- La ricompilazione avviene con il JDK 21, quindi i file di classe compilati per Java 22 o versioni successive vengono letti ed elencati, ma non verificati.
- Non è un antivirus né una sandbox. Le sue JVM ausiliarie isolano i guasti, non il codice ostile.
- Non decompila il codice nativo in Java. Le librerie native incluse possono essere esaminate in modo preliminare, facoltativamente con un Ghidra installato da te.

## Avvio rapido

**Requisito:** un **JDK** Java 21 (non solo un JRE), perché ClassRevive usa javac per verificare i propri risultati. [Eclipse Temurin 21](https://adoptium.net) funziona su Windows, Linux e macOS. `java -version` dovrebbe mostrare 21.

1. Scarica lo zip dell'ultima versione dalla [pagina Releases](https://github.com/t1zzo/classrevive/releases) ed estrailo dove preferisci.
2. Apri l'applicazione desktop:

   ```bash
   bin/classrevive-gui
   ```

   Su Windows, fai doppio clic su `bin\classrevive-gui.bat`. Apri o trascina un `.jar`, premi **F5** per decompilare e leggi ogni classe con il suo livello di affidabilità.
3. Oppure usa la riga di comando:

   ```bash
   bin/classrevive decompile app.jar --engines all
   ```

   Questo crea una nuova cartella `app-classrevive/`: il codice selezionato in `selection/sources/`, una mappa di affidabilità in `report.html`, un progetto per IDE in `project/` e l'output grezzo di ogni motore accanto a essi. Le cartelle esistenti non vengono mai sovrascritte.

`classrevive --help` elenca tutti i comandi e `classrevive help decompile` ne spiega uno. Per compilare invece dai sorgenti, esegui `./gradlew :cli:installDist` con il JDK 21; i lanciatori si trovano in `modules/cli/build/install/classrevive/bin/`.

È la prima volta che decompili? Leggi [Getting started](docs/getting-started.md) (in inglese) o la [guia para iniciantes](docs/guia-iniciante.md) in portoghese.

## Funzionalità

| Area | Cosa ottieni | Documentazione |
|---|---|---|
| Applicazione desktop | Esploratore di pacchetti e classi con filtro di affidabilità, bytecode prima di qualsiasi decompilazione, avanzamento in tempo reale con annullamento, un verdetto in linguaggio semplice, affidabilità per classe e indicatori di andata e ritorno per metodo, errori di javac sottolineati, confronto affiancato dei motori, ricerca degli utilizzi e gerarchia delle chiamate, vai alla classe, ricerca nel codice, esportazione come progetto per IDE, temi scuro e chiaro, inglese e portoghese | [Getting started](docs/getting-started.md#4-the-desktop-application) |
| Selezione verificata | Ricompilazione, firme strutturali, andata e ritorno per metodo, ibridi limitati, correzioni del codice guidate dal compilatore, composizione per metodo tra motori | [Architettura](ARCHITECTURE.md#selection-and-composition) |
| Input | `.class`, `.jar`, jar multi-release, eseguibili con un jar incorporato, raccolte di jar, APK e dex di Android | [Riferimento della CLI](docs/cli-reference.md#executables-bundles-and-android-inputs) |
| Librerie mancanti | Passa i tuoi jar con `--classpath`; altrimenti dei sostituti dedotti permettono al risultato di compilare, e questo viene contrassegnato come basato su stub | [Riferimento della CLI](docs/cli-reference.md#selection-evidence-and-limits) |
| Offuscamento | Profilo statico delle tecniche e ipotesi sull'offuscatore con punteggi; inversione di Skidfuscator, Aidsfuscator, Radon e Zelix KlassMaster 27; un passaggio generale di semplificazione del flusso di controllo; semplificazione dei flag dei predicati opachi; passaggi facoltativi di decodifica di costanti e stringhe | [Rilevamento dell'offuscamento](docs/obfuscation-detection.md) |
| Rinomina | Alias leggibili, file di mappatura espliciti, una mappatura ProGuard/R8 o un log di yGuard per ripristinare i nomi originali dove è sicuro, e nomi dedotti facoltativi contrassegnati con le relative prove | [Riferimento della CLI](docs/cli-reference.md#rename-experimental) |
| Diagnostica | Indizi statici su riflessione, caricamento delle classi, invokedynamic e crittografia; impronta del compilatore; riconoscimento delle librerie incorporate; esame preliminare facoltativo delle librerie native con Ghidra | [Riferimento della CLI](docs/cli-reference.md#diagnose) |
| Report | `report.txt`, `report.json` (schema v1), una mappa di affidabilità autonoma `report.html`, esportazione come progetto per IDE | [Formato dei report](docs/reports.md) |
| Misurazione | Comando di benchmark, una classifica su librerie di Maven Central verificate tramite hash e un laboratorio dei guasti che trasforma i guasti in test di regressione | [Benchmark](docs/benchmarks.md) |

## Come funziona

```text
input (.class / .jar / .apk / .exe)
  │  acquisizione limitata: letto come dati, mai estratto né eseguito
  ▼
normalizzazione ── dex2jar per Android · nomi portabili · versione base dei jar multi-release
  │
  ▼
diagnosi ── profilo di offuscamento; inverte un offuscatore riconosciuto (verificato dal verificatore della JVM)
  │
  ▼
CFR · Vineflower · Procyon · JADX        ognuno nella propria JVM ausiliaria
  │
  ▼
javac in una JVM ausiliaria ── firme per classe · andata e ritorno per metodo
  │
  ▼
selezione ── ordinamento · ibridi · correzioni del codice · composizione per metodo
  │
  ▼
codice selezionato + report.html / report.json + progetto per IDE (+ bytecode di riserva)
```

I dettagli, compreso ciò che ogni fase si rifiuta di fare, sono in [ARCHITECTURE.md](ARCHITECTURE.md) (in inglese).

## Benchmark

La classifica decompila librerie open source reali scaricate da Maven Central e verificate con SHA-256. Nessuna regola di ClassRevive è stata calibrata sui due insiemi di validazione (*holdout*). Misurato il 29/10/2026 con ClassRevive 0.1.0-dev, con tutti e quattro i motori più la selezione combinata, su Windows con il JDK 21:

| Insieme | Librerie | Compilano per intero | Firme | Corpi, libreria intera | Corpi, con credito per file |
|---|---:|---:|---:|---:|---:|
| `holdout` | 3 | 3/3 | 2.372/2.375 (99,8%) | 1.752/1.765 (99,2%) | 1.752/1.765 (99,2%) |
| `holdout2` | 13 | 5/13 | 21.583/52.080 (41,4%) | 8.774/38.445 (22,8%) | 35.899/38.445 (93,3%) |

- **Compilano per intero**: il codice selezionato dell'intera libreria compila con javac rispetto alle dipendenze dichiarate. In `holdout2`, a 8 librerie su 13 restano ancora da 1 a 33 errori di javac.
- **Firme**: classi, campi e metodi la cui struttura corrisponde all'originale, contati sulle classi generate da javac; una libreria che non compila contribuisce poco a questo valore.
- **Corpi, libreria intera**: corpi di metodo identici o equivalenti all'originale dopo la ricompilazione, contati solo per le librerie che compilano per intero. Il denominatore è ogni corpo di metodo dell'originale.
- **Corpi, con credito per file**: lo stesso, ma ogni file di una libreria che non compila viene anche compilato da solo rispetto alle classi originali, e i suoi corpi contano quando quel file compila. Misura quanto del codice è corretto file per file, non un programma che puoi ricostruire.

Nessuno di questi numeri misura il comportamento, e non dicono nulla di certo su altri input, soprattutto quelli offuscati.

Riproducili con una copia del repository e il JDK 21:

```bash
./gradlew :cli:scoreboard -PscoreboardSet=holdout
./gradlew :cli:scoreboard -PscoreboardSet=holdout2
```

Ogni esecuzione scrive `build/scoreboard/<set>-<timestamp>/benchmark.md`. I numeri per libreria, l'insieme `dev` e come leggere ogni colonna sono in [docs/benchmarks.md](docs/benchmarks.md) (in inglese).

## Sicurezza e uso responsabile

ClassRevive non esegue mai il codice che analizza: nessuna classe analizzata viene inizializzata e nessun metodo analizzato viene invocato. Il bytecode viene letto come dati, e alcuni passaggi valutano codice costante puro in un interprete statico limitato. Dopo la riscrittura del bytecode (per esempio con l'inversione di un offuscatore), le classi riscritte vengono collegate, mai inizializzate, in una JVM ausiliaria separata, così che il verificatore della JVM possa controllarle. I decompilatori, javac e gli altri passaggi pesanti girano in JVM ausiliarie separate con limiti di tempo, memoria e output. Si tratta di isolamento dei guasti, **non di una sandbox del sistema operativo**: analizza i file deliberatamente ostili all'interno di una macchina virtuale.

Analizza solo software che hai il diritto di analizzare: il tuo codice, codice open source o casi consentiti dalla tua licenza o dalla legge. Leggi l'[avvertenza legale](DISCLAIMER.md) prima di usare ClassRevive su software di terzi, e consulta [SECURITY.md](SECURITY.md) per segnalare una vulnerabilità. Per favore, non allegare binari privati o proprietari alle issue pubbliche.

I nomi di offuscatori e prodotti (Zelix KlassMaster, Allatori, DashO, Stringer, ProGuard, R8, Skidfuscator, Aidsfuscator e altri) appartengono ai rispettivi proprietari. ClassRevive non è affiliato con nessuno di essi, e il suo rilevamento è euristico.

## Documentazione

- [Getting started](docs/getting-started.md) · [Guia para iniciantes (portoghese)](docs/guia-iniciante.md)
- [Riferimento della riga di comando](docs/cli-reference.md)
- [Architettura](ARCHITECTURE.md) e [stato: cosa funziona e cosa manca](docs/status.md)
- [Formato dei report](docs/reports.md) · [Rilevamento dell'offuscamento](docs/obfuscation-detection.md) · [Semplificazione del flusso di controllo](docs/control-flow.md) · [Radon e yGuard](docs/radon-yguard.md) · [Zelix KlassMaster 27](docs/zelix-klassmaster.md) · [Nomi dedotti](docs/inferred-names.md) · [Diagnostica del collegamento dinamico](docs/dynamic-diagnostics.md)
- [Benchmark](docs/benchmarks.md) · [Registro delle modifiche](CHANGELOG.md)
- Sperimentale: [API dei passaggi](docs/pass-sdk.md), [sandbox](docs/sandbox.md), [protocollo di comportamento](docs/behavior-protocol.md)


## Contribuire

Segnalazioni di bug, casi di test e pull request sono benvenuti; vedi [CONTRIBUTING.md](CONTRIBUTING.md). Il controllo completo è `./gradlew test :cli:installDist :cli:distZip`. Un caso di guasto ridotto a partire dal tuo codice o da codice con licenza aperta, tramite il [laboratorio dei guasti](docs/cli-reference.md#failure-lab), è la segnalazione di bug più utile.

## Licenza

ClassRevive è distribuito con la [ClassRevive License 1.0](LICENSE): libero da usare, anche a fini commerciali, da modificare e ridistribuire citando la fonte; i fork non possono usare il nome né l'identità visiva di ClassRevive. La distribuzione include CFR (MIT), Vineflower, Procyon e JADX (Apache-2.0), ASM (BSD-3-Clause), FlatLaf (Apache-2.0), RSyntaxTextArea (BSD-3-Clause), dex2jar (Apache-2.0) e altre librerie con le rispettive licenze; vedi [NOTICE](NOTICE), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) e [third-party-licenses/](third-party-licenses/).
