"""Measure checkpoint serialization on the host JVM; no Android or game operations."""
from pathlib import Path
import argparse
import json
import subprocess
import tempfile
import tomllib

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jdk', type=Path, required=True)
parser.add_argument('--gradle-home', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
versions = tomllib.loads((root / 'gradle/libs.versions.toml').read_text())['versions']
modules = [('org.jetbrains.kotlin', 'kotlin-stdlib', versions['kotlin']),
           ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', versions['coroutines']),
           ('org.jetbrains.kotlinx', 'kotlinx-serialization-core-jvm', versions['serialization']),
           ('org.jetbrains.kotlinx', 'kotlinx-serialization-json-jvm', versions['serialization'])]
classpath = [root / 'core/build/classes/kotlin/main']
for group, module, version in modules:
    matches = list((args.gradle_home / 'caches/modules-2/files-2.1' / group / module / version).rglob(f'{module}-{version}.jar'))
    if len(matches) != 1:
        raise RuntimeError(f'Expected one cached dependency: {group}:{module}:{version}')
    classpath += matches
source = r'''
import java.util.*;
import io.github.yysassist.core.execution.*;
import io.github.yysassist.core.model.*;
public class CheckpointBenchmark {
  public static void main(String[] args) {
    var context = new ExecutionContext("synthetic", Principal.Anonymous.INSTANCE);
    var engine = new TaskExecutor(context, () -> 1234L);
    var steps = new ArrayList<String>();
    for (int i = 0; i < 20; i++) steps.add("step-" + i);
    var tasks = new ArrayList<TaskSnapshot>();
    String configuration = "{\"note\":\"" + "x".repeat(4096) + "\"}";
    for (int i = 0; i < 40; i++) tasks.add(new TaskSnapshot("task-" + i, "Synthetic task " + i, 1L, steps, configuration));
    engine.enqueue("synthetic-command", new ExecutionRequest(new SubmissionRef(SubmissionKind.PLAN, "synthetic-plan"), context, "fixture-v1", tasks));
    long start = System.nanoTime();
    String initial = engine.encodeState();
    long initialNs = System.nanoTime() - start;
    for (int i = 0; i < 100; i++) engine.stepFinished(engine.getState().getValue().getActiveTicket(), new StepOutcome.Completed("synthetic evidence"));
    start = System.nanoTime();
    String after = engine.encodeState();
    long afterNs = System.nanoTime() - start;
    System.out.println("{\"syntheticOnly\":true,\"taskCount\":40,\"stepsPerTask\":20,\"completedSteps\":100,\"configurationCharactersPerTask\":4096,\"initialBytes\":" + initial.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + ",\"afterBytes\":" + after.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + ",\"initialEncodeNs\":" + initialNs + ",\"afterEncodeNs\":" + afterNs + "}");
  }
}
'''
with tempfile.TemporaryDirectory(prefix='checkpoint-bench-', dir=root / '.local') as temporary:
    work = Path(temporary)
    java = work / 'CheckpointBenchmark.java'
    java.write_text(source)
    cp = ':'.join(map(str, classpath))
    subprocess.run([str(args.jdk / 'bin/javac'), '-cp', cp, '-d', str(work), str(java)], check=True, capture_output=True)
    result = subprocess.run([str(args.jdk / 'bin/java'), '-cp', f'{work}:{cp}', 'CheckpointBenchmark'], check=True, capture_output=True, text=True)
    record = json.loads(result.stdout)
record['scope'] = 'Single un-warmed host-JVM measurement, not an Android latency or 24-hour result; no production threshold set.'
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(record, ensure_ascii=False))
