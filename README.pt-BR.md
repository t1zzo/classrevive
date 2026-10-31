# ClassRevive

**Recupere Java legível a partir de código compilado e meça o quanto dele está certo.**

[English](README.md) | Português (Brasil)

![O aplicativo desktop do ClassRevive mostrando uma classe descompilada do Gson: a árvore de pacotes à esquerda, o código escolhido com uma bolinha verde de ida e volta ao lado de cada método no meio, e o nível de confiança e as evidências da classe à direita](docs/images/desktop-class-dark-pt-BR.png)

O ClassRevive roda quatro descompiladores Java conhecidos (CFR, Vineflower, Procyon e JADX) sobre um `.class`, um `.jar`, um APK do Android ou um executável com um jar dentro. Ele recompila cada resultado com o javac, compara com o bytecode original e fica, arquivo por arquivo e método por método, com a versão que as evidências sustentam. Cada classe termina com um nível de confiança que você pode conferir, no aplicativo desktop ou na linha de comando.

> A documentação de referência é em inglês; este README e o [guia para iniciantes](docs/guia-iniciante.md) são traduzidos. Se houver diferença, vale a versão em inglês.

## O que ele faz

- **Descompila com quatro descompiladores e escolhe por arquivo e por método.** Cada descompilador roda na sua própria JVM. Os candidatos são ordenados por recompilar ou não, por quantas assinaturas estruturais batem com o original e por quantos corpos de método voltam idênticos ou equivalentes depois de recompilados.
- **Confere em vez de adivinhar.** Todo resultado diz o que foi medido: recompilação, assinaturas por classe, ida e volta por método. Resultados parciais guardam uma listagem legível do bytecode do que não pôde ser recuperado.
- **Lida com entradas difíceis.** Jars multi-release, nomes de classe que colidem por maiúsculas e minúsculas, executáveis que carregam um jar, pacotes de jars, APK e dex do Android (convertidos com o dex2jar).
- **Detecta ofuscação e desfaz parte dela.** Um perfil estático aponta as técnicas e o ofuscador que elas indicam, com uma pontuação e os motivos. Dois ofuscadores comunitários (Skidfuscator e Aidsfuscator) são revertidos automaticamente, e as flags de predicado opaco do Zelix KlassMaster comprovadamente constantes são simplificadas.
- **Nunca executa o código que analisa.** As classes analisadas nunca são inicializadas e os métodos delas nunca são chamados. Veja [Segurança](#segurança-e-uso-responsável) para a fronteira exata.

## O que ele não faz

- Não prova que o programa recuperado se comporta como o original. O comportamento é sempre informado como `NOT_VERIFIED`.
- Não traz de volta o que a compilação descartou: comentários, formatação e, muitas vezes, os nomes das variáveis locais.
- Não recupera nomes que um ofuscador trocou, a menos que você forneça o arquivo de mapeamento do ofuscador.
- A recompilação roda no JDK 21; por isso, arquivos `.class` feitos para Java 22 ou mais novo são lidos e listados, mas não conferidos.
- Não é antivírus nem sandbox. As JVMs auxiliares isolam falhas, não código hostil.
- Não descompila código nativo para Java. Bibliotecas nativas incluídas podem passar por triagem, opcionalmente com um Ghidra que você mesmo instala.

## Começo rápido

**Requisito:** um **JDK** do Java 21 (não só o JRE), porque o ClassRevive usa o javac para conferir os resultados. O [Eclipse Temurin 21](https://adoptium.net) funciona no Windows, no Linux e no macOS. `java -version` deve mostrar 21.

1. Baixe o zip da versão mais recente na [página de Releases](https://github.com/t1zzo/classrevive/releases) e descompacte onde quiser.
2. Abra o aplicativo desktop:

   ```bash
   bin/classrevive-gui
   ```

   No Windows, dê dois cliques em `bin\classrevive-gui.bat`. Abra ou arraste um `.jar`, aperte **F5** para descompilar e leia cada classe com o seu nível de confiança.
3. Ou use a linha de comando:

   ```bash
   bin/classrevive decompile app.jar --engines all
   ```

   Isso cria uma pasta nova `app-classrevive/`: o código escolhido em `selection/sources/`, um mapa de confiança em `report.html`, um projeto de IDE em `project/` e, ao lado, a saída bruta de cada descompilador. Pastas existentes nunca são sobrescritas.

`classrevive --help` lista todos os comandos e `classrevive help decompile` explica um deles. Para montar a partir do código-fonte, rode `./gradlew :cli:installDist` com o JDK 21; os atalhos ficam em `modules/cli/build/install/classrevive/bin/`.

Nunca descompilou nada? Leia o [guia para iniciantes](docs/guia-iniciante.md).

## Funcionalidades

| Recurso | O que você tem | Documentação |
|---|---|---|
| Aplicativo desktop | Explorador de pacotes e classes, bytecode antes de descompilar, progresso ao vivo com cancelamento, confiança por classe e marcas de ida e volta por método, erros do javac sublinhados, comparação lado a lado entre descompiladores, encontrar usos e hierarquia de chamadas, ir para classe, pesquisa no código, exportação como projeto de IDE, temas escuro e claro, português e inglês | [Guia para iniciantes](docs/guia-iniciante.md#4-o-aplicativo-desktop) |
| Seleção conferida | Recompilação, assinaturas estruturais, ida e volta por método, híbridos limitados, reparos de código guiados pelo compilador, composição por método entre descompiladores | [Arquitetura](ARCHITECTURE.md#selection-and-composition) (em inglês) |
| Entradas | `.class`, `.jar`, jars multi-release, executáveis com um jar dentro, pacotes de jars, APK e dex do Android | [Referência da CLI](docs/cli-reference.md#executables-bundles-and-android-inputs) (em inglês) |
| Bibliotecas ausentes | Passe os seus jars com `--classpath`; sem eles, substitutos deduzidos deixam o resultado compilar, e ele é marcado como *stub-backed* | [Referência da CLI](docs/cli-reference.md#selection-evidence-and-limits) (em inglês) |
| Ofuscação | Perfil estático das técnicas e hipóteses de ofuscador com pontuação; reversão de Skidfuscator e Aidsfuscator; simplificação de flags de predicado opaco; passes opcionais de constantes e de decodificação de strings | [Detecção de ofuscação](docs/obfuscation-detection.md) (em inglês) |
| Renomeação | Apelidos legíveis, arquivos de mapeamento explícitos ou um mapeamento do ProGuard/R8 para restaurar os nomes originais onde isso é seguro | [Referência da CLI](docs/cli-reference.md#rename-experimental) (em inglês) |
| Diagnóstico | Pistas estáticas de reflexão, carregamento de classes, invokedynamic e criptografia; impressão digital do compilador; reconhecimento de bibliotecas embutidas; triagem opcional de bibliotecas nativas com o Ghidra | [Referência da CLI](docs/cli-reference.md#diagnose) (em inglês) |
| Relatórios | `report.txt`, `report.json` (esquema v1), um mapa de confiança `report.html` autocontido, exportação como projeto de IDE | [Formato dos relatórios](docs/reports.md) (em inglês) |
| Medição | Comando de benchmark, um placar com bibliotecas do Maven Central conferidas por hash e um laboratório de falhas que transforma falhas em testes de regressão | [Benchmarks](docs/benchmarks.md) (em inglês) |

## Como funciona

```text
entrada (.class / .jar / .apk / .exe)
  │  leitura limitada: tratada como dados, nunca extraída nem executada
  ▼
normalização ── dex2jar para Android · nomes portáveis · versão base de jars multi-release
  │
  ▼
diagnóstico ── perfil de ofuscação; reverte um ofuscador reconhecido (conferido pelo verificador da JVM)
  │
  ▼
CFR · Vineflower · Procyon · JADX        cada um na sua própria JVM auxiliar
  │
  ▼
javac numa JVM auxiliar ── assinaturas por classe · ida e volta por método
  │
  ▼
seleção ── ordenação · híbridos · reparos de código · composição por método
  │
  ▼
código escolhido + report.html / report.json + projeto de IDE (+ bytecode de reserva)
```

Os detalhes, incluindo o que cada etapa se recusa a fazer, estão no [ARCHITECTURE.md](ARCHITECTURE.md) (em inglês).

## Benchmarks

O placar descompila bibliotecas reais de código aberto baixadas do Maven Central e conferidas por SHA-256. Nenhuma regra do ClassRevive foi ajustada nos dois conjuntos de validação (*holdout*). Medido em 29/10/2026 com o ClassRevive 0.1.0-dev, com os quatro descompiladores e a seleção combinada, no Windows com o JDK 21:

| Conjunto | Bibliotecas | Compilam inteiras | Assinaturas | Corpos, biblioteca inteira | Corpos, com crédito por arquivo |
|---|---:|---:|---:|---:|---:|
| `holdout` | 3 | 3/3 | 2.372/2.375 (99,8%) | 1.752/1.765 (99,2%) | 1.752/1.765 (99,2%) |
| `holdout2` | 13 | 5/13 | 21.583/52.080 (41,4%) | 8.774/38.445 (22,8%) | 35.899/38.445 (93,3%) |

- **Compilam inteiras**: o código escolhido da biblioteca inteira compila com o javac contra as dependências que ela declara. No `holdout2`, 8 das 13 bibliotecas ainda ficam com 1 a 33 erros do javac.
- **Assinaturas**: classes, campos e métodos cuja estrutura bate com o original, contados nas classes que o javac gerou; uma biblioteca que não compila contribui pouco aqui.
- **Corpos, biblioteca inteira**: corpos de método idênticos ou equivalentes ao original depois de recompilados, contados só nas bibliotecas que compilam inteiras. O denominador é todo corpo de método do original.
- **Corpos, com crédito por arquivo**: o mesmo, mas cada arquivo de uma biblioteca que não compila também é compilado sozinho contra as classes originais, e os corpos dele contam quando esse arquivo compila. Isso mede quanto do código está certo arquivo por arquivo, não um programa que dá para recompilar.

Nenhum desses números mede comportamento, e eles não garantem nada sobre outras entradas, principalmente as ofuscadas.

Para reproduzir, numa cópia do repositório com o JDK 21:

```bash
./gradlew :cli:scoreboard -PscoreboardSet=holdout
./gradlew :cli:scoreboard -PscoreboardSet=holdout2
```

Cada execução grava `build/scoreboard/<conjunto>-<data-hora>/benchmark.md`. Os números por biblioteca, o conjunto `dev` e como ler cada coluna estão em [docs/benchmarks.md](docs/benchmarks.md) (em inglês).

## Segurança e uso responsável

O ClassRevive nunca executa o código que analisa: nenhuma classe analisada é inicializada e nenhum método dela é chamado. O bytecode é lido como dados, e alguns passes avaliam código constante puro num interpretador estático com limites. Depois que o bytecode é reescrito (por exemplo, pela reversão de um ofuscador), as classes reescritas são linkadas, nunca inicializadas, numa JVM auxiliar separada, para que o verificador da JVM as confira. Descompiladores, javac e as outras etapas pesadas rodam em JVMs auxiliares separadas, com limites de tempo, memória e saída. Isso é isolamento de falhas, **não uma sandbox do sistema operacional**: analise arquivos deliberadamente hostis dentro de uma máquina virtual.

Só analise software que você tem o direito de analisar: seu próprio código, código aberto, ou casos que a sua licença ou a lei permitem. Leia o [aviso legal](DISCLAIMER.md) (em inglês) antes de usar o ClassRevive em software de terceiros, e veja o [SECURITY.md](SECURITY.md) (em inglês) para relatar uma vulnerabilidade. Por favor, não anexe binários privados ou proprietários em issues públicas.

Os nomes de ofuscadores e produtos (Zelix KlassMaster, Allatori, DashO, Stringer, ProGuard, R8, Skidfuscator, Aidsfuscator e outros) pertencem aos seus donos. O ClassRevive não tem vínculo com nenhum deles, e a detecção é heurística.

## Documentação

- [Guia para iniciantes](docs/guia-iniciante.md) · [Getting started](docs/getting-started.md) (em inglês)
- [Referência da linha de comando](docs/cli-reference.md) (em inglês)
- [Arquitetura](ARCHITECTURE.md) e [situação: o que funciona e o que falta](docs/status.md) (em inglês)
- [Formato dos relatórios](docs/reports.md) · [Detecção de ofuscação](docs/obfuscation-detection.md) · [Diagnóstico de ligação dinâmica](docs/dynamic-diagnostics.md) (em inglês)
- [Benchmarks](docs/benchmarks.md) · [Changelog](CHANGELOG.md) (em inglês)
- Experimental: [API de passes](docs/pass-sdk.md), [sandbox](docs/sandbox.md), [protocolo de comportamento](docs/behavior-protocol.md) (em inglês)

## Próximos passos

O ClassRevive ainda não chegou à versão 1.0: opções de comando e campos de relatório podem mudar entre versões menores. Os próximos itens da lista:

- Uma imagem de execução por sistema operacional, para não precisar mais instalar um JDK à parte.
- Reversão de mais ofuscadores, cada uma apoiada em fixtures que possam ser redistribuídos.
- Comparação de comportamento em máquinas virtuais descartáveis. A execução do código analisado continua desligada até isso existir, e nunca vai cair para execução na máquina do usuário.

O [docs/status.md](docs/status.md) (em inglês) lista o que funciona hoje, com as evidências, e o que falta.

## Contribuir

Relatos de bugs, fixtures e pull requests são bem-vindos; veja o [CONTRIBUTING.md](CONTRIBUTING.md) (em inglês). A verificação completa é `./gradlew test :cli:installDist :cli:distZip`. O relato de bug mais útil é um caso de falha reduzido a partir de código seu ou de licença aberta, com o [laboratório de falhas](docs/cli-reference.md#failure-lab).

## Licença

O ClassRevive é licenciado sob a [Apache License 2.0](LICENSE). A distribuição inclui CFR (MIT), Vineflower, Procyon e JADX (Apache-2.0), ASM (BSD-3-Clause), FlatLaf (Apache-2.0), RSyntaxTextArea (BSD-3-Clause), dex2jar (Apache-2.0) e outras bibliotecas, cada uma sob a sua licença; veja [NOTICE](NOTICE), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) e [licenses/](licenses/).
