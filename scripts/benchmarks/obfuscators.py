"""Phase 2DK: reproducible Radon 2 and yGuard benchmark with an answer key.

Builds the obfuscator from a pinned commit (it runs only as a tool, in its own JVM, on jars whose source we have), obfuscates
each sample, optionally restores names (yGuard: `rename --yguard-log`), decompiles with ClassRevive and scores the result
against the original jar (`classrevive compare-reference`). No target code is executed: ClassRevive only parses and
decompiles, and javac only compiles the recovered sources.

Licenses: Radon is GPL-3.0 and yGuard MIT. Neither is copied into ClassRevive or committed; the tool sources and builds live
under .analysis/tools/, and nothing this script obfuscates is committed (Radon's output contains code its generators wrote).

Usage (from the repository root, after `./gradlew :cli:installDist`):
  python scripts/benchmarks/obfuscators.py --tool radon --config benchmarks/obfuscated/radon/radon-full.yml \
      --sets fixture,inventario,dev --output .analysis/obf-bench/<name> [--deobfuscate auto|off] [--samples gson,json]
  python scripts/benchmarks/obfuscators.py --tool yguard --config benchmarks/obfuscated/yguard/yguard.xml \
      --sets fixture,inventario --output .analysis/obf-bench/<name>
"""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
WINDOWS = os.name == "nt"
TOOLS = {
    # Radon 2: the last commit before the unfinished Radon 3 rewrite (e7caab8). GPL-3.0.
    "radon": {"repo": "https://github.com/ItzSomebody/radon", "commit": "00ac9929adefedb21057868d77e9c95d2fb459c6"},
    # yGuard 5.0.0 (tag 5.0.0). MIT.
    "yguard": {"repo": "https://github.com/yWorks/yGuard", "commit": "16218758fcfef763322c9092405addd6556d1f91"},
}
# Libraries the tools compile against, taken from the Gradle cache ClassRevive's own build verified.
TOOL_LIBRARIES = {
    "radon": ["org.ow2.asm/asm/9.11.1", "org.ow2.asm/asm-tree/9.11.1", "org.ow2.asm/asm-commons/9.11.1", "org.ow2.asm/asm-util/9.11.1",
              "org.ow2.asm/asm-analysis/9.11.1", "org.yaml/snakeyaml/1.33"],
    "yguard": ["org.ow2.asm/asm/9.11.1", "org.apache.ant/ant/1.10.17", "org.apache.ant/ant-launcher/1.10.17"],
}
MAIN = {"radon": "me.itzsomebody.radon.Main", "yguard": "org.apache.tools.ant.launch.Launcher"}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def run(command, cwd=None, log=None, timeout=3600):
    started = time.time()
    with open(log, "w", encoding="utf-8", errors="replace") if log else open(os.devnull, "w") as out:
        result = subprocess.run(command, cwd=cwd, stdout=out, stderr=subprocess.STDOUT, timeout=timeout)
    return result.returncode, round(time.time() - started, 1)


def classrevive():
    home = pathlib.Path(os.environ.get("CLASSREVIVE_HOME", ROOT / "modules" / "cli" / "build" / "install" / "classrevive"))
    return [str(home / "bin" / ("classrevive.bat" if WINDOWS else "classrevive"))]


def gradle_jar(coordinate):
    group, name, version = coordinate.split("/")
    cache = pathlib.Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1" / group / name / version
    jars = [p for p in cache.rglob(f"{name}-{version}.jar")] if cache.is_dir() else []
    if not jars:
        sys.exit(f"{coordinate} is not in the Gradle cache; run ./gradlew build first")
    return jars[0]


def build_tool(tool, work):
    spec = TOOLS[tool]
    source = work / f"{tool}-src-{spec['commit'][:7]}"
    if not source.is_dir():
        subprocess.run(["git", "clone", "--quiet", spec["repo"], str(source)], check=True)
    subprocess.run(["git", "-C", str(source), "checkout", "--quiet", spec["commit"]], check=True)
    head = subprocess.run(["git", "-C", str(source), "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()
    if head != spec["commit"]:
        sys.exit(f"{tool} checkout is {head}, expected {spec['commit']}")
    libraries = [gradle_jar(c) for c in TOOL_LIBRARIES[tool]]
    classes = source / "build-classrevive" / "classes"
    if not classes.is_dir():
        sources = sorted(str(p) for p in (source / "src" / "main" / "java").rglob("*.java"))
        classes.mkdir(parents=True)
        code, _ = run(["javac", "-nowarn", "-encoding", "UTF-8", "--release", "8", "-proc:none", "-cp", os.pathsep.join(map(str, libraries)),
                       "-d", str(classes)] + sources, log=work / f"{tool}-build.log")
        if code != 0:
            shutil.rmtree(classes)
            sys.exit(f"{tool} build failed; see {work / (tool + '-build.log')}")
        resources = source / "src" / "main" / "resources"
        if resources.is_dir():
            shutil.copytree(resources, classes, dirs_exist_ok=True)
    return [classes] + libraries


def java_base(work):
    """The JDK's java.base classes as a jar, for tools that read their library classes from jars (no rt.jar since Java 9)."""
    jar = work / "java-base.jar"
    if jar.is_file():
        return jar
    home = pathlib.Path(subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True).stderr
                        .split("java.home = ")[1].splitlines()[0].strip())
    extracted = work / "java-base"
    shutil.rmtree(extracted, ignore_errors=True)
    subprocess.run(["jmod", "extract", "--dir", str(extracted), str(home / "jmods" / "java.base.jmod")], check=True)
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as out:
        for path in sorted((extracted / "classes").rglob("*.class")):
            name = path.relative_to(extracted / "classes").as_posix()
            if name != "module-info.class":
                out.write(path, name)
    return jar


def samples(sets, only):
    result = []
    for name in sets:
        if name == "fixture":
            result.append(("fixture", ROOT / "benchmarks" / "obfuscated" / "skidfuscator" / "skid-fixture.jar"))
        elif name == "inventario":
            jar = ROOT / "test-inputs" / "primeiro" / "inventario.jar"
            if jar.is_file():
                result.append(("inventario", jar))
        else:
            gradlew = str(ROOT / ("gradlew.bat" if WINDOWS else "gradlew"))
            subprocess.run([gradlew, ":cli:scoreboardCorpus" + name[0].upper() + name[1:], "-q", "--console=plain"], cwd=ROOT, check=True)
            for jar in sorted((ROOT / "build" / "scoreboard-corpus" / name).glob("*.jar")):
                result.append((name, jar))
    if only:
        result = [(s, j) for s, j in result if any(token in j.name for token in only)]
    return result


def dependencies(jar):
    deps = jar.parent / (jar.name + ".deps")
    return sorted(deps.glob("*.jar")) if deps.is_dir() else []


def obfuscate(tool, classpath, config, jar, deps, base, case):
    """Runs the tool on a copy of the sample without module descriptors and multi-release entries."""
    tool_input = case / "input.jar"
    with zipfile.ZipFile(jar) as source, zipfile.ZipFile(tool_input, "w", zipfile.ZIP_DEFLATED) as target:
        for item in source.infolist():
            if item.filename.endswith("module-info.class") or item.filename.startswith("META-INF/versions/"):
                continue
            target.writestr(item, source.read(item))
    output = case / "obfuscated.jar"
    libraries = [base] + deps
    if tool == "radon":
        text = config.read_text(encoding="utf-8").replace("{input}", tool_input.as_posix()).replace("{output}", output.as_posix())
        lines = []
        for line in text.splitlines():
            if "{libraries}" in line:
                lines.extend(line.replace("{libraries}", lib.as_posix()) for lib in libraries)
            else:
                lines.append(line)
        (case / "radon.yml").write_text("\n".join(lines) + "\n", encoding="utf-8")
        command = ["java", "-cp", os.pathsep.join(map(str, classpath)), MAIN[tool], "--config", str(case / "radon.yml")]
    else:
        command = ["java", "-cp", os.pathsep.join(map(str, classpath)), MAIN[tool], "-f", str(config),
                   f"-Dinput={tool_input}", f"-Doutput={output}", f"-Dlog={case / 'yguardlog.xml'}",
                   f"-Dlibraries={os.pathsep.join(map(str, libraries))}"]
    code, seconds = run(command, cwd=case, log=case / f"{tool}.log")
    return (output if code == 0 and output.is_file() else None), seconds


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tool", choices=sorted(TOOLS), required=True)
    parser.add_argument("--config", required=True)
    parser.add_argument("--sets", default="fixture")
    parser.add_argument("--output", required=True)
    parser.add_argument("--deobfuscate", choices=["auto", "off"], default="auto")
    parser.add_argument("--samples", default="")
    parser.add_argument("--engines", default="all")
    args = parser.parse_args()
    output = pathlib.Path(args.output).resolve()
    if output.exists():
        sys.exit("Output exists; choose a new folder")
    output.mkdir(parents=True)
    work = ROOT / ".analysis" / "tools"
    work.mkdir(parents=True, exist_ok=True)
    config = pathlib.Path(args.config).resolve()
    classpath = build_tool(args.tool, work)
    base = java_base(work)
    rows = []
    for set_name, jar in samples(args.sets.split(","), [s for s in args.samples.split(",") if s]):
        deps = dependencies(jar)
        case = output / f"{set_name}-{jar.stem}"
        case.mkdir()
        row = {"set": set_name, "sample": jar.name, "input_sha256": sha256(jar)}
        obfuscated, seconds = obfuscate(args.tool, classpath, config, jar, deps, base, case)
        row["obfuscate_seconds"] = seconds
        if obfuscated is None:
            row["status"] = "OBFUSCATION_FAILED"
            rows.append(row)
            continue
        row["obfuscated_sha256"] = sha256(obfuscated)
        target = obfuscated
        if args.tool == "yguard" and (case / "yguardlog.xml").is_file():
            code, _ = run(classrevive() + ["rename", str(obfuscated), "--yguard-log", str(case / "yguardlog.xml"), "--output", str(case / "rename")],
                          log=case / "rename.log")
            row["names_restored"] = code == 0
            if code == 0:
                target = case / "rename" / "renamed.jar"
        classpath_deps = os.pathsep.join(str(d) for d in deps)
        command = classrevive() + ["decompile", str(target), "--output", str(case / "decompile"), "--engines", args.engines,
                                   "--timeout-seconds", "1800", "--deobfuscate", args.deobfuscate]
        if deps:
            command += ["--classpath", classpath_deps]
        _, row["decompile_seconds"] = run(command, log=case / "decompile.log")
        command = classrevive() + ["compare-reference", str(jar), str(case / "decompile"), "--output", str(case / "reference"), "--timeout-seconds", "1800"]
        if deps:
            command += ["--classpath", classpath_deps]
        code, _ = run(command, log=case / "reference.log")
        evidence = case / "reference" / "reference.json"
        if code != 0 or not evidence.is_file():
            row["status"] = "COMPARE_FAILED"
            rows.append(row)
            continue
        data = json.loads(evidence.read_text(encoding="utf-8"))
        whole = data["whole_compilation"]
        row.update({"status": "MEASURED", "compiled": whole["status"] == "COMPILED", "errors": whole["errors"],
                    "signatures": [whole["matched_symbols"], whole["expected_symbols"]],
                    "bodies": [data["bodies_recovered"], data["reference_bodies"]],
                    "literals": [data["literals_found"], data["literals"]]})
        rows.append(row)
    summary = {"schema_version": 1, "behavior": "NOT_VERIFIED", "tool": args.tool, "commit": TOOLS[args.tool]["commit"],
               "config": config.name, "config_sha256": sha256(config), "deobfuscate": args.deobfuscate,
               "tool_libraries": {str(p.name): sha256(p) for p in classpath[1:]}, "rows": rows}
    (output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

    def frac(pair):
        return f"{pair[0]}/{pair[1]} ({100.0 * pair[0] / pair[1]:.1f}%)" if pair[1] else "n/a"

    lines = ["| Sample | Compiles? | Signatures | Bodies | Literals |", "|---|---|---:|---:|---:|"]
    for row in rows:
        if row["status"] != "MEASURED":
            lines.append(f"| {row['sample']} | {row['status']} | | | |")
        else:
            lines.append(f"| {row['sample']} | {'yes' if row['compiled'] else 'no, ' + str(row['errors']) + ' errors'} | "
                         f"{frac(row['signatures'])} | {frac(row['bodies'])} | {frac(row['literals'])} |")
    (output / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
