# Guia para iniciantes: como descompilar um programa Java com o ClassRevive

Este guia é para quem nunca descompilou nada. Ele mostra como instalar o ClassRevive, descompilar um `.jar` no aplicativo desktop ou na linha de comando e entender o resultado. A versão em inglês está em [getting-started.md](getting-started.md).

## 1. O que é descompilar

Um programa Java costuma ser distribuído compilado: em vez dos arquivos `.java` que o programador escreveu, você recebe arquivos `.class`, geralmente juntos dentro de um `.jar`. Esses arquivos são para a máquina virtual Java, não para pessoas. **Descompilar** é o caminho de volta: transformar `.class` e `.jar` em código `.java` legível de novo.

O ClassRevive não descompila sozinho. Ele roda **quatro descompiladores conhecidos** (CFR, Vineflower, Procyon e JADX), compara os resultados e fica com o melhor, arquivo por arquivo e até método por método. Depois, **confere** o resultado: recompila o código gerado e compara com o bytecode original.

| Faz | Não faz |
|---|---|
| Gera código `.java` a partir de `.jar`, `.class`, `.apk` do Android ou um `.exe` com um jar dentro | Não recupera comentários, formatação nem a maioria dos nomes de variáveis locais; a compilação apagou isso |
| Testa se o código gerado compila de novo | Não prova que o programa se comporta igual ao original |
| Diz quantos métodos voltaram idênticos ou equivalentes ao original | Não executa o programa analisado (isso é proposital, por segurança) |
| Lê arquivos `.class` de qualquer versão do Java até a 28 | Não recompila nem confere arquivos feitos para Java 22 ou mais novo |

> **Só descompile programas que você tem o direito de analisar**: seu próprio código, código aberto, ou casos que a licença ou a lei permitem. Veja o [aviso legal](../DISCLAIMER.md) (em inglês).

## 2. O que você precisa ter instalado

Só uma coisa: o **JDK do Java 21**.

1. Baixe o **Eclipse Temurin 21 (JDK)** em [adoptium.net](https://adoptium.net) para o seu sistema (no Windows, o instalador `.msi`).
2. Instale com as opções padrão. No Windows, se aparecer a opção **Set JAVA_HOME variable**, marque.
3. Confira: abra um terminal (PowerShell no Windows, Terminal no macOS ou no Linux) e rode:

   ```bash
   java -version
   ```

   Deve aparecer algo com `21`, por exemplo `openjdk version "21.0.12"`. Se o comando não for reconhecido, feche e abra o terminal de novo; se continuar, reinstale marcando a opção de PATH/JAVA_HOME.

> Precisa ser o **JDK**, não só o JRE: o ClassRevive usa o compilador Java para conferir o resultado.

## 3. Baixar o ClassRevive

Baixe o zip da versão mais recente na [página de Releases](https://github.com/t1zzo/classrevive/releases) e descompacte numa pasta à sua escolha, por exemplo `Documentos\classrevive` no Windows ou `~/classrevive` no macOS e no Linux. Dentro dela você encontra:

| Pasta | Conteúdo |
|---|---|
| `bin/` | os atalhos para abrir: `classrevive-gui` (aplicativo desktop) e `classrevive` (linha de comando), com versões `.bat` para Windows |
| `lib/` | o programa e os descompiladores que ele usa |
| `docs/` | esta documentação |
| `licenses/` | as licenças das bibliotecas incluídas |

Para conferir que funciona, abra um terminal nessa pasta e rode:

```bash
bin/classrevive engines
```

No Windows, escreva `bin\classrevive.bat engines`. Devem aparecer os quatro descompiladores (`cfr`, `vineflower`, `procyon`, `jadx`) com as versões.

<details>
<summary>Ou montar a partir do código-fonte</summary>

Com o JDK 21 e uma cópia do repositório, rode `./gradlew :cli:installDist` (`.\gradlew.bat :cli:installDist` no Windows). Na primeira vez isso baixa as dependências e demora alguns minutos. Os atalhos ficam em `modules/cli/build/install/classrevive/bin/`. Veja a [referência da linha de comando](cli-reference.md#build-from-source) (em inglês).

</details>

## 4. O aplicativo desktop

Abra `bin/classrevive-gui` (no Windows, dê dois cliques em `bin\classrevive-gui.bat`). O idioma segue o do sistema e pode ser trocado em **Configurações** (Ctrl+,).

1. **Abra um arquivo.** Use **Abrir arquivo…** ou arraste um `.jar`, `.class`, `.apk` ou `.exe` para a janela. O explorador à esquerda mostra na hora os pacotes, as classes e os recursos; dá para ler o bytecode de qualquer classe antes de descompilar.
2. **Descompile.** Aperte **F5** (ou o botão laranja **Descompilar**). O painel de atividade, embaixo, mostra cada etapa ao vivo, e o botão de parar cancela. **Descompilar com opções…** deixa escolher os descompiladores, o limite de tempo e as bibliotecas de que o programa precisa; os padrões servem para começar.
3. **Leia o resultado.** Clique numa classe. O código escolhido abre com cores, números de linha e dobras. A caixa **Visão**, em cima, troca para a versão de um descompilador específico ou para o bytecode.

O que a tela mostra:

- **Nível de confiança.** Toda classe tem um: *Exata*, *Próxima*, *Por arquivo*, *Parcial* ou *Não verificada* (veja a [seção 6](#6-entendendo-o-resultado)). Ele aparece na árvore, acima do editor e no inspetor à direita.
- **Bolinhas de ida e volta.** No código escolhido, cada método tem uma bolinha na margem: verde quando o corpo recompilado é idêntico ou equivalente ao original, outras cores quando é diferente. Erros do javac aparecem sublinhados.
- **Faixa de ofuscação.** Quando o bytecode aponta para um ofuscador conhecido, uma faixa diz qual, com as evidências. Para Skidfuscator e Aidsfuscator, o ClassRevive pode desfazer a ofuscação antes de descompilar.

Atalhos úteis:

| Tecla | Ação |
|---|---|
| F5 | Descompilar |
| Ctrl+N | Ir para uma classe pelo nome |
| Ctrl+F / Ctrl+Shift+F | Procurar no arquivo atual / pesquisar em todo o código |
| Ctrl+clique, Alt+Esquerda / Alt+Direita | Abrir a classe sob o mouse, voltar e avançar |
| Ctrl+D | Comparar dois descompiladores lado a lado |
| Alt+F7 | Encontrar os usos da classe, do método ou do campo sob o cursor |
| Ctrl+Alt+H | Hierarquia de chamadas de um método |
| Ctrl+, | Configurações: tema (escuro ou claro), idioma (português ou inglês), tamanho da fonte, descompiladores padrão, limite de tempo, pasta de resultados |

Cada descompilação grava uma pasta nova dentro da pasta de resultados (no Windows, por padrão, `%APPDATA%\ClassRevive\workspaces`), com a mesma organização da linha de comando abaixo. **Abrir resultado…** reabre qualquer uma dessas pastas, e **Exportar projeto…** salva o código como projeto de IDE ou como zip.

## 5. A linha de comando

Suponha que você quer descompilar `Downloads/programa.jar`:

```bash
bin/classrevive decompile ~/Downloads/programa.jar --output ~/Downloads/programa-descompilado --engines all
```

No Windows:

```powershell
bin\classrevive.bat decompile "$HOME\Downloads\programa.jar" --output "$HOME\Downloads\programa-descompilado" --engines all
```

| Parte | Significado |
|---|---|
| `decompile` | o que você quer fazer |
| `programa.jar` | o arquivo de entrada (`.jar`, `.class`, `.apk`, `.dex` ou um `.exe` com um jar dentro) |
| `--output ...` | onde guardar o resultado. **Tem que ser uma pasta que ainda não existe**; o ClassRevive cria. Sem `--output`, ele cria `programa-classrevive` na pasta atual |
| `--engines all` | usar os quatro descompiladores. Sem isso ele usa só CFR e Vineflower, que é mais rápido mas costuma dar resultado pior |

Caminhos com espaço precisam de aspas. Um `.jar` pequeno leva algumas dezenas de segundos; um grande (milhares de classes) pode levar vários minutos.

## 6. Entendendo o resultado

### A linha de resumo

No fim, o comando mostra algo assim:

```text
Selected: recompose-1 | compilation=COMPILED | signatures=478/479 | placeholder-signals=1
Sources: selection/sources. Behavior not verified; see selection/selection.txt for evidence.
```

| Trecho | Em português |
|---|---|
| `compilation=COMPILED` | **O código gerado compila.** É o principal sinal de sucesso. `COMPILE_FAILED` quer dizer que parte do código tem erros |
| `signatures=478/479` | Das 479 peças de estrutura do programa (classes, métodos, campos), 478 voltaram com o formato exato do original. Quanto mais perto do total, melhor |
| `placeholder-signals=1` | Trechos que parecem "buracos" deixados por um descompilador. Às vezes é alarme falso: um `throw new UnsupportedOperationException(...)` escrito pelo próprio autor do programa também conta |
| `Behavior not verified` | Lembrete honesto: compilar não prova que o programa faz exatamente a mesma coisa |

### Onde está o código

O código fica em `programa-descompilado/selection/sources/`, em pastas por pacote (por exemplo `org/json/JSONObject.java`). Qualquer editor serve; uma IDE Java, como o IntelliJ IDEA Community ou o VS Code com a extensão Java, deixa a leitura bem mais fácil.

A pasta `programa-descompilado/project/` é um **projeto pronto**: o código em `src/main/java`, os recursos do jar em `src/main/resources`, as bibliotecas passadas com `--classpath` em `lib/` e um `build.gradle`. Abra essa pasta na IDE e importe como projeto Gradle.

### O quanto confiar em cada classe

Abra `programa-descompilado/report.html` no navegador. É um **mapa de confiança**, com uma cor por classe:

| Nível | Significado |
|---|---|
| **exact** (verde) | todos os métodos voltaram idênticos ou equivalentes ao original |
| **close** (verde-claro) | compila, mas alguns métodos ficaram diferentes |
| **per file** (azul) | o programa inteiro não compila, mas o arquivo dessa classe sim |
| **partial** (laranja) | faltam partes da estrutura |
| **unverified** (vermelho) | não deu para conferir, porque o arquivo tem erros |

### As outras coisas na pasta

| Caminho | Para que serve |
|---|---|
| `selection/sources/` | **o resultado final**: o melhor código escolhido |
| `project/` | o mesmo código como projeto de IDE |
| `report.html` | o mapa de confiança |
| `report.txt` | resumo em texto do que aconteceu |
| `report.json` | o mesmo, com todos os detalhes, para scripts ([formato](reports.md), em inglês) |
| `selection/selection.txt` | o diário da escolha: o que cada descompilador conseguiu e o que foi consertado |
| `cfr/`, `vineflower/`, `procyon/`, `jadx/` | o resultado bruto de cada descompilador |
| `fallback/bytecode/` | uma listagem legível das instruções originais, para o que não descompilou |
| `input.jar`, `classes.jar` | cópias da entrada usadas no processo |

### O código de saída (para quem usa scripts)

| Código | Significa |
|---|---|
| `0` | tudo conferido: compila com todas as assinaturas e sem sinais de "buraco" |
| `1` | terminou com algo parcial: assinaturas faltando, sinais de "buraco" ou erros de compilação. **É o caso mais comum, e o código pode estar ótimo mesmo assim**; olhe a linha `Selected:` |
| `2` | comando digitado errado |
| `130` | interrompido (Ctrl+C) |

## 7. Quando o programa depende de outras bibliotecas

A maioria dos programas usa bibliotecas de terceiros que não estão dentro do jar que você descompila. Sem elas, a conferência não consegue compilar tudo. O ClassRevive então deduz substitutos para os tipos que faltam, para ainda conseguir conferir o resultado, e o marca como **stub-backed**: as assinaturas foram comparadas com palpites, não com a biblioteca de verdade. O `report.json` lista o que faltou:

```text
missing packages (supply with --classpath): org.tukaani.xz (27), ...
```

Se você tiver esses jars, passe com `--classpath`, separados por `;` no Windows e por `:` no macOS e no Linux:

```bash
bin/classrevive decompile gson-2.13.1.jar --engines all --classpath error_prone_annotations-2.38.0.jar
```

Os jars passados assim só são lidos pelo compilador durante a conferência. Eles nunca são executados e não entram no resultado. No aplicativo desktop, adicione em **Bibliotecas (opcional)**, dentro de **Descompilar com opções…**.

## 8. Problemas comuns

| Mensagem ou sintoma | O que fazer |
|---|---|
| `Output already exists` | A pasta de `--output` já existe. Escolha outro nome; o ClassRevive nunca apaga resultados antigos |
| `java` não é reconhecido | O JDK 21 não está instalado, ou o terminal foi aberto antes da instalação. Veja a seção 2 |
| `compilation=WORKER_FAILED` | O compilador não rodou. Confira com `javac -version` se o JDK 21 está instalado; só o JRE não basta |
| Demora demais ou `TIMED_OUT` | Aumente o limite por etapa, em segundos: `--timeout-seconds 600` (o padrão é 120, o máximo 3600) |
| `UNSUPPORTED_RELEASE` | O programa foi feito para Java 22 ou mais novo; a recompilação dele ainda não pode ser conferida |
| `compilation=COMPILE_FAILED` | Parte do código não compilou. Veja a seção 7 (bibliotecas faltando). Os arquivos com problema aparecem em `selection/selection.txt`, e o código continua em `selection/sources/` para leitura |

## 9. Outros comandos úteis

| Comando | Para que serve |
|---|---|
| `classrevive inspect arquivo.jar` | mostra o que tem dentro (cada classe e a versão do Java para a qual foi feita), sem descompilar |
| `classrevive identify arquivo.exe` | diz o que é um arquivo e se ele tem Java dentro |
| `classrevive diagnose arquivo.jar` | triagem estática: técnicas de ofuscação, reflexão, carregamento de classes, criptografia |
| `classrevive engines` | lista os descompiladores e as versões |
| `classrevive --help` | lista todos os comandos; `classrevive help <comando>` explica um deles |

Os comandos avançados (`transform`, `rename`, `benchmark`, `lab`) estão na [referência da linha de comando](cli-reference.md) (em inglês).

## 10. Glossário rápido

- **`.class`**: um arquivo compilado de Java (uma classe).
- **`.jar`**: um zip com vários `.class` e, às vezes, imagens e configurações.
- **Descompilador**: programa que faz o caminho de volta, de `.class` para `.java`.
- **Compilar**: transformar `.java` em `.class`. O ClassRevive recompila o resultado para conferir se está certo.
- **Assinatura**: o formato de uma classe, de um método ou de um campo: nome, parâmetros, tipo e modificadores.
- **Ida e volta (round trip)**: recompilar um método descompilado e comparar com o original, instrução por instrução. Se bate, o método voltou fiel.
- **Ofuscação**: embaralhar um programa de propósito para dificultar a leitura, por exemplo trocando todos os nomes por nomes curtos e sem sentido. O ClassRevive detecta ofuscações comuns e desfaz algumas, mas não consegue recuperar nomes trocados sem o arquivo de mapeamento do ofuscador.
