# Reference parity continuation

Goal: recreate observable VirtualSpawner 7.2.0-rc-12 behavior independently in BravenSpawner.
Evidence: supplied local JAR SHA-256 803bfcce299b5b5010afd575970e9253c69f8b232ae791ab313bfabd33c7ea2e; REA inventory ev_fe35bd83184aeba9eecc6683cc1240e20540c890aaf1386764fcf25797396f0a. Static evidence does not establish runtime compatibility.

- [x] Identify JAR; compare previous extraction; inspect resource/bytecode entry points.
- [ ] Connect per-product files to settings, scheduling, virtual loot generation, item display, limits and reload.
- [ ] Connect YAML menus; isolate callbacks by inventory session; support stack purchasing.
- [ ] Connect sell wands and modifiers; settle multiplier in one economy transaction.
- [ ] Connect configured hologram text and optional client/placeholder support.
- [ ] Review unfinished stock/audit/commerce modules against evidence; avoid wiring unsupported behavior.
- [ ] Run meaningful regression tests, full build and inspect output artifact; document remaining parity gaps.

Constraints: preserve existing work and owner data; no automatic Git operations; all world/entity work through owning schedulers; failed payments retain storage. Reference reserve/refill model has no evidence and stays disconnected. Full parity requires runtime Paper/Folia testing and optional-provider validation.
