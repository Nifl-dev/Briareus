# Versioning discipline (Briareus fork)

Formalised 2026-11-24. Before this, the scheme was observed by habit but written
down nowhere, which meant a release could ship with a stale `versionCode` and
nobody would notice until an install refused to upgrade.

## The scheme

`app/build.gradle.kts` carries two values under `defaultConfig`:

- `versionCode = YYMMDDnn` — date-derived, six digits plus a two-digit sequence.
  `26112303` means 2026-11-23, build 03 of that day.
- `versionName = <upstream base>+<sequence>` — tracks the upstream Operit version
  it was forked from, plus the fork's own build sequence. `1.12.2+5` is the fifth
  Briareus build on upstream 1.12.2.

The date-derived `versionCode` exists so every Briareus build increases
monotonically no matter what upstream ships. Upstream's own `versionCode` values
come from their release cadence and would otherwise collide.

## The rules

1. **Every push that produces an installable APK bumps `versionCode`.** A second
   build on the same day increments the trailing sequence (`26112301` -> `26112302`),
   never the date part.
2. **`versionName`'s suffix tracks the fork build number**, not upstream's.
   Increment alongside `versionCode` so the two never disagree about ordering.
3. **Never reuse a `versionCode`.** Android will refuse to install over a same-or-lower
   code for release builds, and the failure looks like a signing fault rather than a
   version fault, which wastes an hour every time.
4. **Tag releases.** Upstream has 37 tags (`v1.7.1` .. `v1.9.1`); the fork should
   tag its own builds so a specific APK can be reproduced from source. Use
   `briareus-v<versionName>` to avoid colliding with upstream's `v*` namespace.
5. **Do not bump for documentation-only commits.** The version describes the
   installable artefact, not the repository activity.

## Quick reference

```bash
# current values
grep -n "versionCode\|versionName" app/build.gradle.kts | head -4

# bump for a new build (edit by hand; there is no generator script)
#   versionCode: keep the date, add 1 to the last two digits
#   versionName: add 1 after the '+' and tag

git tag -a briareus-v1.12.2+6 -m "Briareus build 6 on upstream 1.12.2"
```

## Relationship to upstream

When merging an upstream release, take their `versionName` base but keep the fork's
`YYMMDDnn` `versionCode`. Upstream's code is not comparable to the fork's, and
`versionCode` is what Android actually enforces.
