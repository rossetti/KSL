#!/usr/bin/env bash
#
# Run the twelve transport examples and normalise the capture, so that two runs of the
# same code can be compared byte for byte.
#
#   KSLExamples/gate/gate.sh mytag           capture into KSLExamples/gate/out/
#   KSLExamples/gate/gate.sh mytag --diff    capture, then diff against baseline.out.norm
#   KSLExamples/gate/gate.sh baseline --save replace the committed baseline
#
# The examples are deterministic: every random stream is pinned, so a difference between
# two captures of the same code is a real difference and not noise. That is the whole
# point -- it makes "this refactor changed nothing" a claim you can check rather than one
# you have to believe.
#
# The baseline is only meaningful against the build that produced it. After landing this
# work somewhere else, re-baseline with --save before trusting a diff.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="${GATE_OUT_DIR:-$HERE/out}"
BASELINE="$HERE/baseline.out.norm"

TAG="${1:?usage: gate.sh <tag> [--diff|--save]}"
MODE="${2:-}"
mkdir -p "$OUT"

TASKS=(
    :KSLExamples:guidedPathBenchmark
    :KSLExamples:agvBenchmark
    :KSLExamples:dispatchingRuleComparison
    :KSLExamples:freePathFleetExample
    :KSLExamples:multiFloorHospitalExample
    :KSLExamples:retaskingExample
    :KSLExamples:simpleAgvExample
    :KSLExamples:twoLaneWarehouseExample
    :KSLExamples:twoParadigmsExample
    :KSLExamples:guidePathDisturbancesExample
    :KSLExamples:zoneClosurePolicyExample
    :KSLExamples:crossingArbiterExample
)

if ! "$ROOT/gradlew" --project-dir "$ROOT" --console=plain --no-daemon "${TASKS[@]}" \
        > "$OUT/$TAG.out" 2> "$OUT/$TAG.err"; then
    echo "gate: gradle failed; see $OUT/$TAG.err" >&2
    exit 1
fi

python3 "$HERE/norm.py" < "$OUT/$TAG.out" > "$OUT/$TAG.out.norm"
python3 "$HERE/norm.py" < "$OUT/$TAG.err" > "$OUT/$TAG.err.norm"
echo "gate: captured $TAG ($(wc -l < "$OUT/$TAG.out.norm") lines) -> $OUT/$TAG.out.norm"

case "$MODE" in
    --save)
        cp "$OUT/$TAG.out.norm" "$BASELINE"
        echo "gate: baseline replaced from $TAG"
        ;;
    --diff)
        if [ ! -f "$BASELINE" ]; then
            echo "gate: no baseline at $BASELINE; run with --save first" >&2
            exit 2
        fi
        if diff -u "$BASELINE" "$OUT/$TAG.out.norm" > "$OUT/$TAG.diff"; then
            echo "gate: IDENTICAL to baseline"
        else
            echo "gate: DIFFERS from baseline -- $OUT/$TAG.diff" >&2
            head -40 "$OUT/$TAG.diff" >&2
            exit 1
        fi
        ;;
esac
