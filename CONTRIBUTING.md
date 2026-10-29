# Contributing

Thanks for taking the time. This project is small enough that a good issue is
often more valuable than a patch — start there if anything is unclear.

## Ground rules

1. **No reflection.** Paper ships the region scheduler API; use it directly.
   A reflective call is a compile-time error we cannot see, so it lands as a
   runtime failure on someone else's server.
2. **Folia is not optional.** Every task goes through
   `Bukkit.getRegionScheduler()`, `entity.getScheduler()` or the async scheduler.
   `Bukkit.getScheduler()` is acceptable only on the global path.
3. **Fail closed.** A missing world, an unloaded chunk, an offline owner — skip the
   tick. Never spawn half a cycle, never write half a record.
4. **One owner per invariant.** Persistence belongs to `storage/`, scheduling to
   `manager/`, presentation to `gui/`. Do not reach across.

## Workflow

```bash
git switch -c feat/short-name
# ... edits ...
JAVA_HOME=/path/to/jdk21 gradle --no-daemon build
git commit -m "feat(scope): what changed and why"
git push -u origin feat/short-name
```

Open a pull request against `main`. CI must be green; a maintainer reviews within a
few days.

## Commit messages

Conventional Commits, present tense, subject ≤ 72 characters:

```
feat(gui): paged storage with instant navigation
fix(corruption): repair radius counted stacked spawners once
refactor(storage): move item codec out of SqlStorage
docs: document booster multipliers
```

Body explains **why**, not what — the diff already shows what.

## Style

- Java 21, four-space indent, no tabs.
- `final` classes by default; mutability is opt-in and named.
- Javadoc on public methods explains the decision, not the mechanics.
- No `TODO`, no stub that compiles but lies. If it is not done, it is not merged.
- Config keys are documented inline in `config.yml` — an undocumented switch is a bug.

## Testing

```bash
gradle --no-daemon build          # compile + unit tests
```

Manual checklist before a PR that touches runtime behaviour:

1. `/vspawner give ZOMBIE` → place → wait one interval → storage fills.
2. Break it → contents drop, item returns.
3. Restart → spawner and contents survive.
4. Repeat on Folia if the change touches scheduling.

## Reporting bugs

Use the issue template. Include the log excerpt around the failure — a crash report
without a stack trace is usually not actionable.
