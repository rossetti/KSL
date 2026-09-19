# The example gate

A fingerprint of the twelve transport examples, so that "this refactor changed nothing" is a
claim you can check rather than one you have to believe.

```
KSLExamples/gate/gate.sh mytag --diff     # run the twelve, compare against the baseline
KSLExamples/gate/gate.sh mytag            # run and capture, compare later yourself
KSLExamples/gate/gate.sh baseline --save  # adopt this run as the new baseline
```

`--diff` exits non-zero and prints the first forty lines of the difference, so it drops
straight into a script or a hook.

## Why it works

Every random stream in the twelve examples is pinned, so the examples are deterministic: two
runs of the same code produce the same numbers. Any difference between two captures is
therefore a real difference in behaviour, not noise — which is what makes a whole-file diff the
right comparison, rather than a tolerance on a handful of summary statistics.

This caught things a test suite did not. A change can leave 3,000 unit tests green and still
move a queue length in the fourth example, because no test asserted on that number. The gate
asserts on all of them at once, including the ones nobody thought to write a test for.

## What is normalised away

`norm.py` removes only what genuinely varies between runs of identical code:

- wall-clock times, elapsed times and throughput rates, masked to `<elapsed>` / `<rate>`
- log timestamps at the start of a line
- Gradle's own bookkeeping

Gradle **task** lines are dropped in full, not just the up-to-date ones. Which tasks Gradle
chooses to run depends on what happens to be cached, so an unrelated change in another module
can add a compile line to an otherwise identical capture. That is a fact about the build rather
than about the models, and leaving it in makes the diff lie. (It did, once.)

Everything else is compared byte for byte — every statistic, every half-width, every warning.

## The baseline is local

`baseline.out.norm` is 6,532 lines captured on one machine against one build. It is committed so
that drift *within this repository* is visible, not as a cross-machine claim: JIT timing is
masked, but a different JDK or a different platform can still legitimately move a figure.

**After moving this work to another repository, re-baseline before trusting a diff:** run
`gate.sh baseline --save` once on the landing build, confirm the examples look sane, and commit
that. A diff against an inherited baseline you have not reproduced tells you nothing useful.

`out/` is ignored, so captures do not accumulate in the repository.
