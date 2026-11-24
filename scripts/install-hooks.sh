#!/usr/bin/env bash
# Install the local git hooks this fork relies on.
#
# Why a local hook instead of CI: the fine-grained GitHub token lacks the
# `workflow` scope, so a workflow file cannot be pushed and CI never sees it.
# Rather than block the CJK gate on token surgery, enforcement lives in
# .git/hooks/pre-commit. This script makes that reproducible on a fresh clone,
# since .git/hooks is not tracked by git.
#
# Usage:  scripts/install-hooks.sh
# Bypass once with:  git commit --no-verify
set -euo pipefail
repo_root=$(git rev-parse --show-toplevel)
hooks_dir="$repo_root/.git/hooks"
mkdir -p "$hooks_dir"
cat > "$hooks_dir/pre-commit" <<"HOOK"
#!/usr/bin/env bash
# Local CJK literal gate. See scripts/install-hooks.sh for why this is not CI.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 0
if ! command -v python3 >/dev/null 2>&1; then
  echo "pre-commit: python3 not found, skipping CJK gate" >&2
  exit 0
fi
out=$(python3 scripts/check-cjk-baseline.py 2>&1)
rc=$?
if [ $rc -ne 0 ]; then
  echo "" >&2
  echo "COMMIT BLOCKED: new CJK string literals detected" >&2
  echo "$out" >&2
  echo "" >&2
  echo "Move user-facing text into res/values, or record a deliberate" >&2
  echo "literal in the baseline:  python3 scripts/check-cjk-baseline.py --update" >&2
  echo "Bypass once with: git commit --no-verify" >&2
  exit 1
fi
echo "pre-commit: CJK gate clean ($out)"
exit 0
HOOK
chmod +x "$hooks_dir/pre-commit"
echo "installed: $hooks_dir/pre-commit"
