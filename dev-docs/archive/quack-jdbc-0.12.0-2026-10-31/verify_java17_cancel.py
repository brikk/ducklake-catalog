"""Run the preserved live cancellation witness with only driver-version/name substitutions.

Usage: python3 verify_java17_cancel.py JAR CLASSPATHS_JSON OUTPUT_DIR JAVA17
Classpath JSON comes from the archived triage classpath resolver. No old driver or
embedded DuckDB JDBC is allowed on this independent direct-client classpath.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

jar, classpaths, output, java = map(Path, sys.argv[1:])
assert hashlib.sha256(jar.read_bytes()).hexdigest() == "0fa0cc634d35cb2a77ff88232fe1f6a3fd1570ba54729c9cc50005c7b575f1cf"
output.mkdir(parents=True, exist_ok=True)
classes = output / "classes"
classes.mkdir(exist_ok=True)
original = Path(__file__).resolve().parent.parent / "quack-jdbc-0.11.1-2026-10-30/Java17Quack0111Smoke.java"
source = output / "Java17Quack012Smoke.java"
source.write_text(original.read_text().replace("Java17Quack0111Smoke", "Java17Quack012Smoke")
                  .replace("0.11.1", "0.12.0").replace("catalog_0111", "catalog_012"))
entries = list(dict.fromkeys(p for paths in json.loads(classpaths.read_text()).values() for p in paths
                            if "duckdb_jdbc" not in p and "quack-jdbc-" not in p))
cp = os.pathsep.join([str(jar.resolve())] + entries)
compile_result = subprocess.run([str(java.with_name("javac")), "--release", "17", "-cp", cp,
                                 "-d", str(classes), str(source)], capture_output=True, text=True, timeout=120)
(output / "compile.log").write_text(compile_result.stdout + compile_result.stderr)
assert compile_result.returncode == 0, compile_result.stderr
result = subprocess.run([str(java), "-Xmx256m", "-cp", str(classes) + os.pathsep + cp, "Java17Quack012Smoke"],
                        capture_output=True, text=True, timeout=120)
(output / "stdout.log").write_text(result.stdout)
(output / "stderr.log").write_text(result.stderr)
print(result.stdout)
assert result.returncode == 0, result.stderr
