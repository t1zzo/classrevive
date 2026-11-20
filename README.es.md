# ClassRevive

**Recupera Java legible a partir de código compilado y mide cuánto de él es correcto.**

[English](README.md) | [Português (Brasil)](README.pt-BR.md) | Español | [Italiano](README.it.md) | [Français](README.fr.md)

![La aplicación de escritorio de ClassRevive mostrando una clase descompilada de Gson: el árbol de paquetes a la izquierda, el código seleccionado con un punto verde de ida y vuelta junto a cada método en el centro, y el nivel de confianza y la evidencia de la clase a la derecha](docs/images/desktop-class-dark.png)

ClassRevive ejecuta cuatro descompiladores de Java conocidos (CFR, Vineflower, Procyon y JADX) sobre un `.class`, un `.jar`, un APK de Android o un ejecutable con un jar incrustado. Recompila cada resultado con javac, lo compara con el bytecode original y conserva, archivo por archivo y método por método, la versión que respalda la evidencia. Cada clase termina con un nivel de confianza que puedes comprobar, en una aplicación de escritorio o en la línea de comandos.

> La documentación de referencia está en inglés; este README está traducido. Si hay alguna diferencia, prevalece la versión en inglés.

## Qué hace

- **Descompila con cuatro motores y elige por archivo y por método.** Cada motor se ejecuta en su propia JVM. Los candidatos se ordenan según si recompilan, cuántas firmas estructurales coinciden con el original y cuántos cuerpos de método vuelven idénticos o equivalentes tras la recompilación.
- **Verifica en lugar de adivinar.** Cada resultado indica qué se midió: recompilación, firmas por clase, una ida y vuelta por método. Los resultados parciales conservan un listado de bytecode legible de lo que no se pudo recuperar.
- **Maneja entradas difíciles.** Jars multi-release, nombres de clase que colisionan por mayúsculas y minúsculas, ejecutables que llevan un jar, paquetes de jars, archivos APK y dex de Android (convertidos con dex2jar).
- **Detecta la ofuscación y deshace parte de ella.** Un perfil estático nombra las técnicas y el ofuscador al que apuntan, con una puntuación y los motivos. Tres ofuscadores de código abierto (Skidfuscator, Aidsfuscator y Radon) y Zelix KlassMaster 27 se revierten automáticamente, y se pliegan los flags de predicados opacos de Zelix KlassMaster que se demuestra que son constantes.
- **Nunca ejecuta el código que analiza.** Las clases analizadas nunca se inicializan y sus métodos nunca se invocan. Consulta [Seguridad](#seguridad-y-uso-responsable) para conocer el límite exacto.

## Qué no hace

- No demuestra que el programa recuperado se comporte como el original. El comportamiento siempre se informa como `NOT_VERIFIED`.
- No puede recuperar lo que la compilación descartó: comentarios, formato y, a menudo, los nombres de las variables locales.
- No recupera los nombres originales que eliminó un ofuscador. Puedes proporcionar el archivo de mapeo del ofuscador (ProGuard, R8, yGuard) o pedir nombres inferidos (`--infer-names`), que se marcan como conjeturas junto con su evidencia.
- La recompilación se ejecuta en el JDK 21, así que los archivos de clase compilados para Java 22 o posterior se leen y se listan, pero no se verifican.
- No es un escáner de malware ni un sandbox. Sus JVM auxiliares aíslan fallos, no código hostil.
- No descompila código nativo a Java. Las bibliotecas nativas incluidas pueden analizarse de forma preliminar, opcionalmente con un Ghidra que instales tú mismo.

## Inicio rápido

**Requisito:** un **JDK** de Java 21 (no solo un JRE), porque ClassRevive usa javac para comprobar sus resultados. [Eclipse Temurin 21](https://adoptium.net) funciona en Windows, Linux y macOS. `java -version` debería mostrar 21.

1. Descarga el zip de la última versión desde la [página de Releases](https://github.com/t1zzo/classrevive/releases) y descomprímelo en cualquier lugar.
2. Abre la aplicación de escritorio:

   ```bash
   bin/classrevive-gui
   ```

   En Windows, haz doble clic en `bin\classrevive-gui.bat`. Abre o arrastra un `.jar`, pulsa **F5** para descompilar y lee cada clase con su nivel de confianza.
3. O usa la línea de comandos:

   ```bash
   bin/classrevive decompile app.jar --engines all
   ```

   Esto crea una carpeta nueva `app-classrevive/`: el código seleccionado en `selection/sources/`, un mapa de confianza en `report.html`, un proyecto de IDE en `project/` y la salida sin procesar de cada motor junto a ellos. Las carpetas existentes nunca se sobrescriben.

`classrevive --help` lista todos los comandos y `classrevive help decompile` explica uno. Para compilar desde el código fuente, ejecuta `./gradlew :cli:installDist` con el JDK 21; los lanzadores quedan en `modules/cli/build/install/classrevive/bin/`.

¿Es tu primera vez descompilando? Lee [Getting started](docs/getting-started.md) (en inglés) o la [guia para iniciantes](docs/guia-iniciante.md) en portugués.

## Funcionalidades

| Área | Qué obtienes | Documentación |
|---|---|---|
| Aplicación de escritorio | Explorador de paquetes y clases con filtro de confianza, bytecode antes de descompilar, progreso en vivo con cancelación, un veredicto en lenguaje claro, confianza por clase y marcas de ida y vuelta por método, errores de javac subrayados, comparación de motores lado a lado, búsqueda de usos y jerarquía de llamadas, ir a una clase, búsqueda en el código, exportación como proyecto de IDE, temas oscuro y claro, inglés y portugués | [Getting started](docs/getting-started.md#4-the-desktop-application) |
| Selección verificada | Recompilación, firmas estructurales, ida y vuelta por método, híbridos acotados, reparaciones de código guiadas por el compilador, composición por método entre motores | [Arquitectura](ARCHITECTURE.md#selection-and-composition) |
| Quinto motor opcional | JD-Core, a partir de un jar que usted aporta (GPL-3.0, nunca incluido ni descargado); solo se ejecuta cuando se pide, y su resultado solo se conserva cuando no es peor que el de los cuatro motores | [JD-Core](docs/jd-core.md) (en inglés) |
| Entradas | `.class`, `.jar`, jars multi-release, ejecutables con un jar incrustado, paquetes de jars, APK y dex de Android | [Referencia de la CLI](docs/cli-reference.md#executables-bundles-and-android-inputs) |
| Bibliotecas ausentes | Pasa tus propios jars con `--classpath`; si no, unos sustitutos inferidos permiten que el resultado compile, y este se marca como basado en stubs | [Referencia de la CLI](docs/cli-reference.md#selection-evidence-and-limits) |
| Ofuscación | Perfil estático de técnicas e hipótesis de ofuscador con puntuaciones; reversión de Skidfuscator, Aidsfuscator, Radon y Zelix KlassMaster 27; un pase general de simplificación del flujo de control; plegado de flags de predicados opacos; pases opcionales de decodificación de constantes y cadenas | [Detección de ofuscación](docs/obfuscation-detection.md) |
| Renombrado | Alias legibles, archivos de mapeo explícitos, un mapeo de ProGuard/R8 o un registro de yGuard para restaurar los nombres originales donde sea seguro, y nombres inferidos opcionales marcados con su evidencia | [Referencia de la CLI](docs/cli-reference.md#rename-experimental) |
| Diagnóstico | Pistas estáticas de reflexión, carga de clases, invokedynamic y criptografía; huella del compilador; reconocimiento de bibliotecas incrustadas; análisis preliminar opcional de bibliotecas nativas con Ghidra | [Referencia de la CLI](docs/cli-reference.md#diagnose) |
| Informes | `report.txt`, `report.json` (esquema v1), un mapa de confianza autocontenido `report.html`, exportación como proyecto de IDE | [Formato de los informes](docs/reports.md) |
| Medición | Comando de benchmark, una tabla de resultados sobre bibliotecas de Maven Central verificadas por hash y un laboratorio de fallos que convierte los fallos en pruebas de regresión | [Benchmarks](docs/benchmarks.md) |

## Cómo funciona

```text
entrada (.class / .jar / .apk / .exe)
  │  lectura acotada: se lee como datos, nunca se extrae ni se ejecuta
  ▼
normalización ── dex2jar para Android · nombres portables · versión base de los jars multi-release
  │
  ▼
diagnóstico ── perfil de ofuscación; revierte un ofuscador reconocido (comprobado por el verificador de la JVM)
  │
  ▼
CFR · Vineflower · Procyon · JADX        cada uno en su propia JVM auxiliar
  │
  ▼
javac en una JVM auxiliar ── firmas por clase · ida y vuelta por método
  │
  ▼
selección ── ordenación · híbridos · reparaciones de código · composición por método
  │
  ▼
código seleccionado + report.html / report.json + proyecto de IDE (+ bytecode de respaldo)
```

Los detalles, incluido lo que cada paso se niega a hacer, están en [ARCHITECTURE.md](ARCHITECTURE.md) (en inglés).

## Benchmarks

La tabla de resultados descompila bibliotecas reales de código abierto descargadas de Maven Central y verificadas con SHA-256. Ninguna regla de ClassRevive se ajustó con los dos conjuntos de validación (*holdout*). Medido el 29/10/2026 con ClassRevive 0.1.0-dev, con los cuatro motores más la selección combinada, en Windows con el JDK 21:

| Conjunto | Bibliotecas | Compilan completas | Firmas | Cuerpos, biblioteca completa | Cuerpos, con crédito por archivo |
|---|---:|---:|---:|---:|---:|
| `holdout` | 3 | 3/3 | 2372/2375 (99,8 %) | 1752/1765 (99,2 %) | 1752/1765 (99,2 %) |
| `holdout2` | 13 | 5/13 | 21 583/52 080 (41,4 %) | 8774/38 445 (22,8 %) | 35 899/38 445 (93,3 %) |

- **Compilan completas**: el código seleccionado de toda la biblioteca compila con javac contra las dependencias que declara. En `holdout2`, a 8 de las 13 bibliotecas todavía les quedan entre 1 y 33 errores de javac.
- **Firmas**: clases, campos y métodos cuya estructura coincide con el original, contados sobre las clases que javac generó; una biblioteca que no compila aporta poco aquí.
- **Cuerpos, biblioteca completa**: cuerpos de método idénticos o equivalentes al original tras la recompilación, contados solo en las bibliotecas que compilan completas. El denominador es cada cuerpo de método del original.
- **Cuerpos, con crédito por archivo**: lo mismo, pero cada archivo de una biblioteca que no compila también se compila por separado contra las clases originales, y sus cuerpos cuentan cuando ese archivo compila. Mide cuánto del código es correcto archivo por archivo, no un programa que puedas reconstruir.

Ninguna de estas cifras mide el comportamiento, y no dicen nada seguro sobre otras entradas, sobre todo las ofuscadas.

Reprodúcelas con una copia del repositorio y el JDK 21:

```bash
./gradlew :cli:scoreboard -PscoreboardSet=holdout
./gradlew :cli:scoreboard -PscoreboardSet=holdout2
```

Cada ejecución escribe `build/scoreboard/<set>-<timestamp>/benchmark.md`. Las cifras por biblioteca, el conjunto `dev` y cómo leer cada columna están en [docs/benchmarks.md](docs/benchmarks.md) (en inglés).

## Seguridad y uso responsable

ClassRevive nunca ejecuta el código que analiza: no se inicializa ninguna clase analizada ni se invoca ningún método analizado. El bytecode se lee como datos, y algunos pases evalúan código constante puro en un intérprete estático acotado. Después de reescribir el bytecode (por ejemplo, al revertir un ofuscador), las clases reescritas se enlazan, sin inicializarse nunca, en una JVM auxiliar separada para que el verificador de la JVM pueda comprobarlas. Los descompiladores, javac y los demás pasos pesados se ejecutan en JVM auxiliares separadas con límites de tiempo, memoria y salida. Eso es aislamiento de fallos, **no un sandbox del sistema operativo**: analiza los archivos deliberadamente hostiles dentro de una máquina virtual.

Analiza solo software que tengas derecho a analizar: tu propio código, código abierto o casos que tu licencia o la ley permitan. Lee el [aviso legal](DISCLAIMER.md) antes de usar ClassRevive con software de terceros, y consulta [SECURITY.md](SECURITY.md) para informar de una vulnerabilidad. Por favor, no adjuntes binarios privados o propietarios en issues públicas.

Los nombres de ofuscadores y productos (Zelix KlassMaster, Allatori, DashO, Stringer, ProGuard, R8, Skidfuscator, Aidsfuscator y otros) pertenecen a sus propietarios. ClassRevive no está afiliado a ninguno de ellos, y su detección es heurística.

## Documentación

- [Getting started](docs/getting-started.md) · [Guia para iniciantes (portugués)](docs/guia-iniciante.md)
- [Referencia de la línea de comandos](docs/cli-reference.md)
- [Arquitectura](ARCHITECTURE.md) y [estado: qué funciona y qué falta](docs/status.md)
- [Formato de los informes](docs/reports.md) · [Detección de ofuscación](docs/obfuscation-detection.md) · [Simplificación del flujo de control](docs/control-flow.md) · [Radon y yGuard](docs/radon-yguard.md) · [Zelix KlassMaster 27](docs/zelix-klassmaster.md) · [Nombres inferidos](docs/inferred-names.md) · [Diagnóstico de enlace dinámico](docs/dynamic-diagnostics.md)
- [Benchmarks](docs/benchmarks.md) · [Registro de cambios](CHANGELOG.md)
- Experimental: [API de pases](docs/pass-sdk.md), [sandbox](docs/sandbox.md), [protocolo de comportamiento](docs/behavior-protocol.md)


## Contribuir

Los informes de errores, los casos de prueba y las pull requests son bienvenidos; consulta [CONTRIBUTING.md](CONTRIBUTING.md). La comprobación completa es `./gradlew test :cli:installDist :cli:distZip`. Un caso de fallo reducido a partir de tu propio código o de código con licencia abierta, mediante el [laboratorio de fallos](docs/cli-reference.md#failure-lab), es el informe de error más útil.

## Licencia

ClassRevive se distribuye bajo la [ClassRevive License 1.0](LICENSE): uso libre, incluso comercial, modificación y redistribución con atribución; los forks no pueden usar el nombre ni la identidad visual de ClassRevive. La distribución incluye CFR (MIT), Vineflower, Procyon y JADX (Apache-2.0), ASM (BSD-3-Clause), FlatLaf (Apache-2.0), RSyntaxTextArea (BSD-3-Clause), dex2jar (Apache-2.0) y otras bibliotecas bajo sus propias licencias; consulta [NOTICE](NOTICE), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) y [third-party-licenses/](third-party-licenses/). El motor opcional JD-Core no forma parte de la distribución: usted aporta su jar (GPL-3.0) y se ejecuta en su propia JVM; vea [docs/jd-core.md](docs/jd-core.md) (en inglés).
