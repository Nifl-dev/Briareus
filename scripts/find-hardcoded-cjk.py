#!/usr/bin/env python3
"""
Detect CJK text inside Kotlin string literals.

Why this exists: the app's default locale is now English, but the source still
carries thousands of Chinese log messages, and a few Chinese strings had leaked
into code paths where they reach the user (a getString() fallback in
StreamGroup.kt shipped Chinese whenever its context was null).

Scope rules, deliberately narrow:
  * Kotlin sources under every module root in ROOTS below -- not just :app.
    Scoping this to app-only originally left module code (e.g. :terminal)
    invisible to the gate.
  * Only CJK inside a STRING literal, never inside a comment. Comments are a
    separate, much larger question (~547 files) and translating them would turn
    every upstream merge into a conflict. XML resources are also out of scope
    here; scripts/check-default-locale.py covers those.
  * A literal is classified INFO when the call it belongs to is a logger call
    (AppLogger.*, StreamLogger.*, *Log.*). Everything else is FAIL, because
    that is text which can reach a user or a tool result.

Implementation note: this tokenises the whole file rather than matching line by
line. An earlier line-based version reported 1284 failures that were all
multi-line logger calls, because the logger name sits on a line above the
literal. Tracking the enclosing call across newlines removes that entire class
of false positive.

Exit codes: 0 = no FAIL findings, 1 = FAIL present, 2 = tool error.

Usage:
    python3 find-hardcoded-cjk.py               # report
    python3 find-hardcoded-cjk.py --fail-only   # only FAIL entries
    python3 find-hardcoded-cjk.py --stats       # counts only
"""

import os
import re
import sys

# Every Kotlin source root in the build, not just :app. Scoped to app-only
# originally, which meant module code (e.g. :terminal) was invisible to the gate.
ROOTS = [
    "app/src/main/java",
    "terminal/src/main/java",
    "llm/llama/src/main/java",
    "llm/mnn/src/main/java",
    "quickjs/src/main/java",
    "avator/dragonbones/src/main/java",
    "avator/fbx/src/main/java",
    "avator/mmd/src/main/java",
    "showerclient/src/main/java",
]
# Build-cache / generated trees: vendored upstream deps keep Chinese in their
# demo apps and comments. Never user-visible, gitignored, must not be counted.
EXCLUDE_DIRS = {".cxx", "build", ".gradle", "generated"}
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")

# Names that identify a logging call. The codebase has both logger objects
# (AppLogger.d, StreamLogger.e) and thin wrappers (logError, logDebug,
# logToolPkgInfo, logSpeechState) that forward to them. Missing the wrappers was
# the reason an earlier pass reported 1140 bogus failures.
LOGGER_NAME = re.compile(r"^(?:AppLogger|StreamLogger|\w*Log|log[A-Z]\w*)$")
CALL_OPEN = re.compile(r"([A-Za-z_][A-Za-z0-9_.]*)\s*\(")


def tokenize(text):
    """
    Yield (kind, value, line, call_context) for each token we care about.

    kind is 'string' or 'code'. call_context is the name of the innermost
    enclosing call still open at the point the token starts, or None.
    """
    i = 0
    n = len(text)
    line = 1
    call_stack = []          # list of call names, innermost last
    paren_stack = []         # True where the '(' belongs to a call we tracked

    while i < n:
        ch = text[i]

        if ch == "\n":
            line += 1
            i += 1
            continue

        # --- line comment
        if text[i:i + 2] == "//":
            end = text.find("\n", i)
            i = n if end == -1 else end
            continue

        # --- block comment (nestable in Kotlin)
        if text[i:i + 2] == "/*":
            depth = 1
            i += 2
            while i < n and depth:
                if text[i:i + 2] == "/*":
                    depth += 1
                    i += 2
                elif text[i:i + 2] == "*/":
                    depth -= 1
                    i += 2
                else:
                    if text[i] == "\n":
                        line += 1
                    i += 1
            continue

        # --- raw string
        if text[i:i + 3] == '"""':
            start_line = line
            j = i + 3
            buf = []
            while j < n and text[j:j + 3] != '"""':
                if text[j] == "\n":
                    line += 1
                buf.append(text[j])
                j += 1
            j = min(j + 3, n)
            ctx = call_stack[-1] if call_stack else None
            yield ("string", "".join(buf), start_line, ctx)
            i = j
            continue

        # --- ordinary string
        if ch == '"':
            start_line = line
            j = i + 1
            buf = []
            while j < n:
                if text[j] == "\\" and j + 1 < n:
                    buf.append(text[j])
                    buf.append(text[j + 1])
                    if text[j + 1] == "\n":
                        line += 1
                    j += 2
                    continue
                if text[j] == '"':
                    break
                if text[j] == "\n":      # unterminated; bail out
                    break
                buf.append(text[j])
                j += 1
            j = min(j + 1, n)
            ctx = call_stack[-1] if call_stack else None
            yield ("string", "".join(buf), start_line, ctx)
            i = j
            continue

        # --- call open / paren tracking
        match = CALL_OPEN.match(text, i)
        if match and not ch.isspace():
            name = match.group(1)
            simple = name.split(".")[-1] if "." in name else name
            # Track the full dotted name (e.g. AppLogger.d) for classification.
            call_stack.append(name)
            paren_stack.append(True)
            i = match.end()
            continue

        if ch == "(":
            paren_stack.append(False)
            i += 1
            continue

        if ch == ")":
            if paren_stack:
                if paren_stack.pop():
                    if call_stack:
                        call_stack.pop()
            i += 1
            continue

        i += 1


def is_logger_call(name):
    """
    True when the enclosing call is a logging call.

    Handles three shapes seen in this codebase:
      AppLogger.d(...)        -> dotted receiver, method name 'd'
      logError(...)           -> bare wrapper
      this.logDebug(...)      -> dotted, wrapper as the final segment
    """
    if not name:
        return False
    parts = name.split(".")
    # Check the final segment (the called function) and, for dotted logger
    # objects, the receiver immediately before it.
    if LOGGER_NAME.match(parts[-1]):
        return True
    if len(parts) >= 2 and LOGGER_NAME.match(parts[-2]):
        return True
    return False


def scan_file(path):
    try:
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
    except (OSError, UnicodeDecodeError):
        return []

    findings = []
    for kind, value, line, ctx in tokenize(text):
        if kind != "string" or not CJK.search(value):
            continue
        classification = "INFO" if is_logger_call(ctx) else "FAIL"
        findings.append((classification, line, value.strip()[:110], ctx))
    return findings


def main():
    fail_only = "--fail-only" in sys.argv
    stats_only = "--stats" in sys.argv

    roots = [r for r in ROOTS if os.path.isdir(r)]
    if not roots:
        print("error: no source roots found (run from the repo root)", file=sys.stderr)
        return 2

    fails, infos, scanned = [], [], 0
    targets = []
    for root in roots:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
            for name in filenames:
                if name.endswith(".kt"):
                    targets.append(os.path.join(dirpath, name))
    for path in sorted(targets):
        if True:
            scanned += 1
            for classification, line, value, ctx in scan_file(path):
                entry = (path, line, value, ctx)
                (fails if classification == "FAIL" else infos).append(entry)

    if stats_only:
        print(f"kotlin files scanned : {scanned}")
        print(f"FAIL (user-visible)  : {len(fails)}")
        print(f"INFO (log arguments) : {len(infos)}")
        return 1 if fails else 0

    if fails:
        print("=== FAIL: CJK in a string literal outside a logger call ===")
        for path, line, value, ctx in sorted(fails):
            print(f"{path}:{line}: {value}")
        print(f"\n{len(fails)} FAIL finding(s)\n")
    else:
        print("=== FAIL: none ===\n")

    if not fail_only and infos:
        print(f"=== INFO: CJK log arguments ({len(infos)}) - backlog, not a failure ===")
        for path, line, value, ctx in sorted(infos)[:15]:
            print(f"{path}:{line}: {value}")
        if len(infos) > 15:
            print(f"... and {len(infos) - 15} more")
        print()

    print(f"summary: {scanned} files, {len(fails)} FAIL, {len(infos)} INFO")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())