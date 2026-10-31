"""Phase 2CD: reproducible Skidfuscator benchmark with an answer key.

Builds Skidfuscator from a pinned commit (it runs only as a tool; the obfuscated jars are the targets), obfuscates
jars whose source we have, decompiles each obfuscated jar with ClassRevive and scores the result against the original
jar (`classrevive compare-reference`). No target code is executed: ClassRevive only parses and decompiles, and javac only
compiles the recovered sources.

Skidfuscator draws its keys from an unseeded java.util.Random, so every run produces different bytes. The script fixes
the tool commit and configuration and records the SHA-256 of every input and obfuscated jar in summary.json; the
reversal must work for any draw, so a benchmark is a sample of draws, not one fixed file.

Usage (from the repository root, after `./gradlew :cli:installDist`):
  python scripts/benchmarks/skidfuscator.py --sets fixture,dev,holdout --output .analysis/skid-bench/<name>
      [--passes skidfuscator] [--deobfuscate auto|off] [--runs 1] [--samples gson,asm]
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

SKIDFUSCATOR_REPO = "https://github.com/skidfuscatordev/skidfuscator-java-obfuscator"
SKIDFUSCATOR_COMMIT = "e4c0f981911703e86ac8a2b1a77d7cfd18b9d719"  # 2026-09-23, version 2.1.0, MIT
ROOT = pathlib.Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "benchmarks" / "obfuscated" / "skidfuscator"
WINDOWS = os.name == "nt"


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


def gradlew():
    return [str(ROOT / ("gradlew.bat" if WINDOWS else "gradlew"))]


def classrevive():
    # CLASSREVIVE_HOME: an installed copy (so a long run is not disturbed by rebuilding the working tree).
    home = pathlib.Path(os.environ.get("CLASSREVIVE_HOME", ROOT / "modules" / "cli" / "build" / "install" / "classrevive"))
    return [str(home / "bin" / ("classrevive.bat" if WINDOWS else "classrevive"))]


def build_skidfuscator(work):
    source = work / "skidfuscator-src"
    if not source.is_dir():
        subprocess.run(["git", "clone", "--quiet", SKIDFUSCATOR_REPO, str(source)], check=True)
    subprocess.run(["git", "-C", str(source), "checkout", "--quiet", SKIDFUSCATOR_COMMIT], check=True)
    head = subprocess.run(["git", "-C", str(source), "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()
    if head != SKIDFUSCATOR_COMMIT:
        sys.exit(f"Skidfuscator checkout is {head}, expected {SKIDFUSCATOR_COMMIT}")
    tool = source / "dev.skidfuscator.client.standalone" / "build" / "libs" / "client-standalone-all.jar"
    if not tool.is_file():
        wrapper = source / ("gradlew.bat" if WINDOWS else "gradlew")
        code, _ = run([str(wrapper), ":client-standalone:shadowJar", "-x", "test", "--console=plain"], cwd=source, log=work / "skidfuscator-build.log")
        if code != 0 or not tool.is_file():
            sys.exit("Skidfuscator build failed; see " + str(work / "skidfuscator-build.log"))
    return tool


def fixture_jar(work):
    """Compiles the authored fixture with javac --release 8 into a jar with fixed timestamps and sorted entries."""
    classes = work / "fixture-classes"
    shutil.rmtree(classes, ignore_errors=True)
    sources = sorted(str(p) for p in (FIXTURE / "src").rglob("*.java"))
    subprocess.run(["javac", "--release", "8", "-proc:none", "-encoding", "UTF-8", "-d", str(classes)] + sources, check=True)
    jar = work / "samples" / "fixture" / "skid-fixture.jar"
    jar.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as out:
        for path in sorted(classes.rglob("*.class")):
            info = zipfile.ZipInfo(path.relative_to(classes).as_posix(), (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, path.read_bytes())
    return jar


def samples(work, sets, only):
    result = []
    for name in sets:
        if name == "fixture":
            result.append(("fixture", fixture_jar(work)))
            continue
        task = ":cli:scoreboardCorpus" + name[0].upper() + name[1:]
        subprocess.run(gradlew() + [task, "-q", "--console=plain"], cwd=ROOT, check=True)
        corpus = ROOT / "build" / "scoreboard-corpus" / name
        for jar in sorted(corpus.glob("*.jar")):
            result.append((name, jar))
    if only:
        result = [(s, j) for s, j in result if any(token in j.name for token in only)]
    return result


def dependencies(jar):
    deps = jar.parent / (jar.name + ".deps")
    return sorted(deps.glob("*.jar")) if deps.is_dir() else []


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sets", default="fixture")
    parser.add_argument("--output", required=True)
    parser.add_argument("--passes", default="")
    # Phase 2DD: decompile reverses a recognized obfuscator by itself; "off" measures the engines on the obfuscated classes.
    parser.add_argument("--deobfuscate", choices=["auto", "off"], default="auto")
    parser.add_argument("--runs", type=int, default=1)
    parser.add_argument("--samples", default="")
    parser.add_argument("--engines", default="all")
    parser.add_argument("--reuse", default="", help="earlier output folder whose obfuscated jars are measured again (same draws)")
    args = parser.parse_args()
    output = pathlib.Path(args.output).resolve()
    if output.exists():
        sys.exit("Output exists; choose a new folder")
    output.mkdir(parents=True)
    work = ROOT / ".analysis"
    work.mkdir(exist_ok=True)
    reuse = pathlib.Path(args.reuse).resolve() if args.reuse else None
    tool = None if reuse else build_skidfuscator(work)
    configs = {"fixture": FIXTURE / "skidfuscator.hocon"}
    library_config = FIXTURE / "skidfuscator-libraries.hocon"
    rows = []
    for set_name, jar in samples(work, args.sets.split(","), [s for s in args.samples.split(",") if s]):
        deps = dependencies(jar)
        for draw in range(1, args.runs + 1):
            case = output / f"{set_name}-{jar.stem}-{draw}"
            case.mkdir()
            libs = case / "libs"
            libs.mkdir()
            for dep in deps:
                shutil.copy(dep, libs / dep.name)
            obfuscated = case / "obfuscated.jar"
            config = configs.get(set_name, library_config)
            # Skidfuscator fails on module descriptors ("Failed to compute some libraries!"): it gets the jar without
            # module-info.class and META-INF/versions/; the answer key stays the original jar.
            tool_input = case / "input.jar"
            with zipfile.ZipFile(jar) as source, zipfile.ZipFile(tool_input, "w", zipfile.ZIP_DEFLATED) as target:
                for item in source.infolist():
                    if item.filename.endswith("module-info.class") or item.filename.startswith("META-INF/versions/"):
                        continue
                    target.writestr(item, source.read(item))
            # -notrack: Skidfuscator otherwise sends analytics. The tool runs in its own JVM; it is not a target. It writes
            # error dumps and a mappings/ cache into its working directory, so it runs inside the case folder.
            if reuse:
                earlier = reuse / case.name
                if (earlier / "obfuscated.jar").is_file():
                    shutil.copy(earlier / "obfuscated.jar", obfuscated)
                    shutil.copy(earlier / "skidfuscator.log", case / "skidfuscator.log")
                code, seconds = 0, 0.0
            else:
                code, seconds = run(["java", "-jar", str(tool), "obfuscate", "-notrack", "-cfg", str(config), "-li", str(libs),
                                     "-o", str(obfuscated), str(tool_input)], cwd=case, log=case / "skidfuscator.log")
            row = {"set": set_name, "sample": jar.name, "draw": draw, "input_sha256": sha256(jar), "obfuscate_seconds": seconds,
                   "config": config.name}
            if (case / "skidfuscator.log").is_file() and b"Exception" in (case / "skidfuscator.log").read_bytes():
                row["skidfuscator_exceptions"] = True
            if code != 0 or not obfuscated.is_file():
                row["status"] = "OBFUSCATION_FAILED"
                rows.append(row)
                continue
            # Skidfuscator 2.1.0 leaves class files older than Java 8 untouched: such a sample is no Skidfuscator sample.
            log = (case / "skidfuscator.log").read_bytes() if (case / "skidfuscator.log").is_file() else b""
            if b"Executed String Encryption [Success: 0 " in log and b"Executed Flow Condition [Success: 0 " in log:
                row["status"] = "NOT_OBFUSCATED"
                rows.append(row)
                continue
            row["obfuscated_sha256"] = sha256(obfuscated)
            target = obfuscated
            if args.passes:
                code, seconds = run(classrevive() + ["transform", str(obfuscated), "--output", str(case / "transform"),
                                                     "--passes", args.passes, "--max-changes", "10000", "--timeout-seconds", "3600"],
                                    log=case / "transform.log")
                row["transform_seconds"] = seconds
                reversed_jar = case / "transform" / "worker" / "transformed" / "transformed.jar"
                if code != 0 or not reversed_jar.is_file():
                    row["status"] = "TRANSFORM_FAILED"
                    rows.append(row)
                    continue
                target = reversed_jar
            classpath = os.pathsep.join(str(d) for d in deps)
            command = classrevive() + ["decompile", str(target), "--output", str(case / "decompile"), "--engines", args.engines,
                                       "--timeout-seconds", "1800", "--deobfuscate", args.deobfuscate]
            if deps:
                command += ["--classpath", classpath]
            _, seconds = run(command, log=case / "decompile.log")
            row["decompile_seconds"] = seconds
            command = classrevive() + ["compare-reference", str(jar), str(case / "decompile"), "--output", str(case / "reference"),
                                       "--timeout-seconds", "1800"]
            if deps:
                command += ["--classpath", classpath]
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
                        "literals": [data["literals_found"], data["literals"]],
                        "files_naming_foreign_classes": [data["files_naming_foreign_classes"], data["reference_source_files"]]})
            rows.append(row)
    summary = {"schema_version": 1, "behavior": "NOT_VERIFIED", "skidfuscator_commit": SKIDFUSCATOR_COMMIT,
               "skidfuscator_config_sha256": {path.name: sha256(path) for path in [configs["fixture"], library_config]},
               "passes": args.passes, "deobfuscate": args.deobfuscate, "rows": rows}
    (output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    lines = ["| Sample | Draw | Compiles? | Signatures | Bodies | Literals | Files naming obfuscator classes |",
             "|---|---:|---|---:|---:|---:|---:|"]

    def frac(pair):
        return f"{pair[0]}/{pair[1]} ({100.0 * pair[0] / pair[1]:.1f}%)" if pair[1] else "n/a"

    for row in rows:
        if row["status"] != "MEASURED":
            lines.append(f"| {row['sample']} | {row['draw']} | {row['status']} | | | | |")
            continue
        lines.append(f"| {row['sample']} | {row['draw']} | {'yes' if row['compiled'] else 'no, ' + str(row['errors']) + ' errors'} | "
                     f"{frac(row['signatures'])} | {frac(row['bodies'])} | {frac(row['literals'])} | {frac(row['files_naming_foreign_classes'])} |")
    measured = [r for r in rows if r["status"] == "MEASURED"]
    if measured:
        total = lambda key: [sum(r[key][0] for r in measured), sum(r[key][1] for r in measured)]
        lines.append(f"| **all** | | {sum(r['compiled'] for r in measured)}/{len(measured)} | {frac(total('signatures'))} | "
                     f"{frac(total('bodies'))} | {frac(total('literals'))} | {frac(total('files_naming_foreign_classes'))} |")
    (output / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
