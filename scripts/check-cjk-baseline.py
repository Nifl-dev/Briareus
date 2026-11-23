#!/usr/bin/env python3
"""
Run the CJK literal checker and compare against a stored baseline.

Purpose: this codebase carries ~1080 CJK string literals that are NOT all bugs.
Several hundred are deliberate product content (Chinese LLM system prompts,
bilingual character-card and tag data, TTS voice identifiers). Translating those
blindly would break behaviour. The baseline freezes the inherited backlog so the
gate can still catch NEW leaks, which is the part that actually rots over time.

Usage:
    python3 scripts/check-cjk-baseline.py            # compare against baseline
    python3 scripts/check-cjk-baseline.py --update   # rewrite the baseline

Exit 1 on new findings; exit 0 otherwise.
"""

import os
import subprocess
import sys

BASELINE = "scripts/cjk-literal-baseline.txt"
CHECKER = "scripts/find-hardcoded-cjk.py"

# Every module root the checker scans. An earlier version filtered with
# line.startswith("app/src"), which silently discarded every finding from the
# other modules -- so :terminal could not regression-protect itself. Keep this
# list in sync with ROOTS in find-hardcoded-cjk.py.
MODULE_ROOTS = (
    "app/src",
    "terminal/src",
    "llm/",
    "quickjs/src",
    "avator/",
    "showerclient/src",
)


def is_module_finding(line):
    return line.startswith(MODULE_ROOTS)


def current_findings():
    out = subprocess.run(
        [sys.executable, CHECKER, "--fail-only"],
        capture_output=True, text=True, check=False,
    ).stdout
    return sorted(
        line for line in out.splitlines() if is_module_finding(line)
    )


def load_baseline():
    if not os.path.exists(BASELINE):
        return []
    with open(BASELINE, encoding="utf-8") as handle:
        return sorted(
            line.rstrip("\n")
            for line in handle
            if is_module_finding(line)
        )


def main():
    findings = current_findings()

    if "--update" in sys.argv:
        with open(BASELINE, "w", encoding="utf-8") as handle:
            handle.write(
                "# Inherited CJK string literals, frozen 2026-11-23.\n"
                "# Each line is file:line:text. Regenerate with:\n"
                "#   python3 scripts/check-cjk-baseline.py --update\n"
                "# Entries are removed as they get translated; new ones fail CI.\n"
            )
            handle.write("\n".join(findings))
            handle.write("\n")
        print(f"baseline written: {len(findings)} entries -> {BASELINE}")
        return 0

    baseline = load_baseline()
    baseline_set = set(baseline)
    finding_set = set(findings)

    new = [item for item in findings if item not in baseline_set]
    fixed = [item for item in baseline if item not in finding_set]

    if new:
        print(f"=== {len(new)} NEW CJK literal(s) not in the baseline ===")
        for item in new:
            print(f"  + {item}")
        print()

    if fixed:
        print(f"=== {len(fixed)} baseline entr(y/ies) no longer present ===")
        print("    Run --update to drop them from the baseline.")
        for item in fixed[:10]:
            print(f"  - {item}")
        if len(fixed) > 10:
            print(f"  ... and {len(fixed) - 10} more")
        print()

    print(
        f"findings={len(findings)}  baseline={len(baseline)}  "
        f"new={len(new)}  resolved={len(fixed)}"
    )
    return 1 if new else 0


if __name__ == "__main__":
    sys.exit(main())