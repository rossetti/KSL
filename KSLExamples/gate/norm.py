#!/usr/bin/env python3
"""Normalise an example-suite capture so two runs of the same code compare byte for byte.

Masks wall-clock readings, which differ every run, and drops Gradle's own bookkeeping.
Everything else -- every statistic, every warning, every report line -- is left alone,
because that is the part the fingerprint exists to compare.

Gradle task lines are dropped in full rather than only the cached ones. Which tasks Gradle
chose to execute depends on what happens to be up to date, so a change in a *different*
module can add a compile line to an otherwise identical capture. That is a fact about the
build, not about the models, and leaving it in makes the diff lie.
"""
import re
import sys

DROP = re.compile(
    r'^(> Task :'
    r'|> Configure project'
    r'|BUILD SUCCESSFUL'
    r'|BUILD FAILED'
    r'|Consider enabling configuration cache'
    r'|\d+ actionable tasks:'
    r'|Picked up JAVA_TOOL_OPTIONS'
    r'|Deprecated Gradle features)'
)
TS = re.compile(r'^\d\d:\d\d:\d\d\.\d\d\d ')
SUBS = [
    (re.compile(r'^(warm-up \(JIT\): [\d,]+ traversals in ).*$'), r'\1<elapsed>'),
    (re.compile(r'^(\s*wall clock\s+: ).*$'), r'\1<elapsed>'),
    (re.compile(r'^(\s*throughput\s+: ).*$'), r'\1<rate>'),
    (re.compile(r'^(\s*wall clock \(s\)\s+)[\d.,]+\s+[\d.,]+\s*$'), r'\1<elapsed>'),
    (re.compile(r'^(\s*traversals / minute)\s+[\d.,]+\s+[\d.,]+\s*$'), r'\1 <rate>'),
    (re.compile(r'^(Beginning Execution Time: ).*$'), r'\1<elapsed>'),
    (re.compile(r'^(End Execution Time: ).*$'), r'\1<elapsed>'),
    (re.compile(r'^(Elapsed Execution Time: ).*$'), r'\1<elapsed>'),
    (re.compile(r'^(Max Allowed Execution Time: ).*$'), r'\1<elapsed>'),
]

for line in sys.stdin:
    line = line.rstrip('\n')
    if DROP.search(line):
        continue
    line = TS.sub('', line)
    for pat, rep in SUBS:
        new = pat.sub(rep, line)
        if new != line:
            line = new
            break
    print(line)
