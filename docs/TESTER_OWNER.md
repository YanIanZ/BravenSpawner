# Owner Test Plan & Release Sign-off

**Baseline:** v1.0.1 examined on 2026-10-30. **Status:** Empty test record; no cases marked passed. This checklist is for the **owner/manual tester**, supported by CI tests from implementers.

## 1. Test environment record

Fill before each campaign:

| Field | Actual value |
| --- | --- |
| Test date / tester | TBD |
| Git commit / PR / JAR SHA-256 | TBD |
| Plugin version | TBD |
| Paper/Folia exact build | TBD |
| Java vendor + version | TBD |
| CPU / RAM / JVM flags | TBD |
| Database type + version | TBD |
| World/chunk/player count | TBD |
| Plugins (Vault, claims, shop, PAPI etc.) | TBD |
| Backup reference + restore result | TBD |

**Never run fault-injection tests on live production data.** Use a copied server with disposable balances and copied DB/world snapshots.

## 2. Functional and integrity matrix

| ID | Scenario | Expected result | Result |
| --- | --- | --- | --- |
| OWN-01 | Give → place → break → place virtual stack item | Total virtual stack count conserved; no orphan record | NOT RUN |
| OWN-02 | Place matching vs different kind/product spawners | Only valid kinds/products are accepted; invalid item unchanged | NOT RUN |
| OWN-03 | Owner/trusted/stranger/admin stack, booster, GUI, break | Exactly configured permissions, no unauthorized consumption | NOT RUN |
| OWN-04 | Chunk limit reached while placing/stacking | Refusal with zero partial mutation | NOT RUN |
| OWN-05 | Two viewers, rapid menu/page/close/reopen, drag/number key/shift click | No stuck lock, ghost session, dupes or lost items | NOT RUN |
| OWN-06 | Storage nearly full + collect all | Only accepted items leave storage; remainder conserved | NOT RUN |
| OWN-07 | Hopper with full/partial inventory, reload and chunk unload | Accurate accepted transfer, no dropped/duplicated remainder | NOT RUN |
| OWN-08 | Production, autosell and GUI withdrawal simultaneously | Consistent inventory totals and confirmed payouts | NOT RUN |
| OWN-09 | Vault deposit rejected, exception, provider missing | No unconfirmed payout results in consumed stock | NOT RUN |
| OWN-10 | Rapid upgrade with insufficient funds, disconnect and reload | Exactly one cost, correct resulting tier/schedule | NOT RUN |
| OWN-11 | Break while XP stored or tool has Mending | XP preservation follows documented policy | NOT RUN |
| OWN-12 | Corrupt, repair, save, reload | Correct corruption state; no phantom countdown | NOT RUN |
| OWN-13 | Restart/reload/unregister during production | One active task per spawner, no double yield | NOT RUN |
| OWN-14 | World rollback, missing anchor, restore option | No overwrite of another block without explicit operator policy | NOT RUN |
| OWN-15 | Command permission, aliases, completion | Commands match documentation and plugin.yml | NOT RUN |
| OWN-16 | Booster configured duration, feature toggles | Runtime behavior matches validated config | NOT RUN |

## 3. Durability and failure injection

- **DUR-01:** Graceful shutdown and restart. Compare spawner UUIDs, virtual stack totals, stored items, XP, tier, ownership and corruption.
- **DUR-02:** Forced process termination after a checkpoint and after a pending mutation. Compare against documented checkpoint RPO; record any queued work that was not durable.
- **DUR-03:** Disable SQL service during write; restore DB. Verify warning, retry, fail-closed mode and absence of destructive empty-table flush.
- **DUR-04:** Corrupted inventory payload/invalid YAML; verify quarantine/error instead of silent zeroed storage.
- **DUR-05:** Concurrent player GUI and autosell during restart; inspect payout ledger and items.
- **DUR-06:** Backup + staging restore; confirm row/slot/XP counts before deployment.
- **DUR-07:** Failed schema migration and old-plugin rollback; verify recoverable backup and no split version writes.

Record actual outcomes as **PASS / FAIL / BLOCKED / NOT RUN** with logs and repeatable steps. A baseline run of v1.0.1 may intentionally expose failures; don't certify it.

## 4. Folia/Paper runtime

Run each supported version independently. In Folia, use players and spawners in different regions/chunks simultaneously; move/teleport between regions; unload and reload chunks; close inventory and disconnect during operations; remove holograms while regions unload; disable plugin on active server. Inspect logs for wrong-thread access, scheduler exceptions, PDC errors and entity leaks.

Record precise build IDs and whether the server is truly Folia, not just Paper with `folia-supported: true`.

## 5. Performance and load procedure

Workloads: 100, 1,000, 10,000 **virtual spawner units**; each with a documented breakdown of number of physical blocks, stacked units, mob/item ratios, active chunks and players. Run idle baseline, virtual production, physical mob production, collect/hopper and autosell load as separate cases.

| Metric | Baseline | Candidate | Result |
| --- | --- | --- | --- |
| Median / p95 / p99 MSPT | TBD | TBD | NOT RUN |
| TPS distribution | TBD | TBD | NOT RUN |
| CPU / heap / GC | TBD | TBD | NOT RUN |
| Active entities / scheduler tasks | TBD | TBD | NOT RUN |
| DB queued writes / p95 flush latency | TBD | TBD | NOT RUN |
| Total item/XP/payout accounting | TBD | TBD | NOT RUN |
| Error count / wrong-thread exceptions | TBD | TBD | NOT RUN |

Use identical worlds, seed/config, server version, hardware, Java and sampling duration for a fair A/B. Record warmup length and sample window. Do not claim percentage improvement without data.

## 6. Release decision template

```text
Release candidate:
Commit / JAR SHA-256:
Test environment:
P0 findings closed (IDs):
CI: PASS / FAIL (link)
Manual functional: PASS / FAIL (logs)
Durability: PASS / FAIL (logs)
Paper matrix: PASS / FAIL
Folia matrix: PASS / FAIL
Migration + rollback: PASS / FAIL
Performance evidence: link / N/A
Known limitations:
Owner decision: APPROVE / REJECT
Owner name + date:
```

**Hard rule:** Any outstanding P0 data-integrity or security finding prevents production sign-off regardless of benchmark performance.
