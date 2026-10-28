"""Run archived negative witnesses; never edits project sources, pins or test goldens."""
import json
import os
from pathlib import Path
import subprocess
import sys

archive = Path(__file__).resolve().parent
work = Path(sys.argv[1]).resolve()
classes = work / "classes"
fixtures = work / "fixtures"
classes.mkdir(parents=True, exist_ok=True)
fixtures.mkdir(exist_ok=True)
line = next(line for line in reversed((work / "classpaths.json").read_text().splitlines()) if line.startswith("{"))
paths = json.loads(line)
classpath = os.pathsep.join(dict.fromkeys(path for group in paths.values() for path in group))


def run(label, command, timeout=90, structured=False):
    result = subprocess.run(command, text=True, capture_output=True, timeout=timeout)
    (work / (label + ".stdout")).write_text(result.stdout)
    (work / (label + ".stderr")).write_text(result.stderr)
    result.check_returncode()
    if structured:
        data = json.loads(result.stdout.splitlines()[-1])
        if any(isinstance(value, dict) and "probe_error" in value for value in data.values()):
            raise RuntimeError(f"Probe setup/execution error; inspect {label}.stdout and .stderr")
        (work / (label + ".json")).write_text(json.dumps(data, indent=2) + "\n")
    print(label + ": " + str(work / (label + (".json" if structured else ".stdout"))))


run("compile", ["mise", "exec", "--", "javac", "--release", "17", "-cp", classpath, "-d", str(classes)] +
    [str(path) for path in sorted(archive.glob("*.java"))])
classpath = str(classes) + os.pathsep + classpath
java = ["mise", "exec", "--", "java", "-Xmx256m", "-Djava.io.tmpdir=" + str(fixtures), "-cp", classpath]
for label, name in [("current", "TriageProbe"), ("n18", "RewriteInlineDeleteProbe"),
                    ("r01", "HistoricalStatsTypeProbe"), ("r04", "IsolationProbe"),
                    ("n13", "CommitMessagePolicyProbe")]:
    run(label, java + [name, str(fixtures)], structured=True)
run("java17", [os.environ["JAVA17"], "-Xmx256m", "-Djava.io.tmpdir=" + str(fixtures), "-cp", classpath,
               "TriageProbe", str(fixtures), "format-only"], structured=True)
run("bounded-parser", ["mise", "exec", "--", "java", "-Xmx32m", "-cp", classpath,
                       "TriageProbe", "huge-loop"], timeout=10)
