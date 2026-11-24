# Versioning discipline (Briareus fork)

Formalised 2026-11-24. Before this, the scheme was observed by habit but written
down nowhere, which meant a release could ship with a stale `versionCode` and
nobody would notice until an install refused to upgrade.

## The scheme

`app/build.gradle.kts` carries two values under `defaultConfig`:

- `versionCode = YYMMDDnn` — date-derived, six digits plus a two-digit sequence.
  `26112303` means 2026-11-23, build 03 of that day.
- `versionName = <upstream base>+<sequence>` — tracks the upstream Operit version
  it was forked from, plus the forks own build sequence. is the fifth
