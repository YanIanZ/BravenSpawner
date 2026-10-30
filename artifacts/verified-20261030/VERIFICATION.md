# Verified continuation — 2026-10-30

`./gradlew clean build --console=plain` succeeded with Gradle9.8.2 and Java21.
**77 tests passed; zero failures, errors or skipped tests.**

Plugin version: `1.1.0-dev`. JAR SHA-256: `f4fe3dd39b8848790fe2419e30ab0456e7c9588c5ca46fc51e2d92b6b687a73b`.
The JAR contains40 mob definitions, five menus, Mongo/events/boosters and relocated
dependencies. It contains no original VirtualSpawner classes and no optional shop
or WorldGuard plugin implementations. The plugin version is a declared resource
processing input, preventing stale version metadata after incremental builds.

Tests cover pure rules, models and mocked server/driver boundaries. No Paper/Folia
server, live economy/shop/claim stack or database server was launched. Optional APIs
compile against genuine published artifacts; they are not bundled. See [parity](FEATURE_PARITY.md).

Verified source: `/Volumes/Ext/DevCache/agent-skills/cache/bravenspawner/continuation-20261030/workspace`. The shared checkout remains under another OpenCode
session's edits, so this snapshot was not automatically merged over those edits.
The source ZIP is standalone; the patch is relative to commit `761cee2c10729ca728b8c05bb03b84deef2d34c1` and is for
review against that baseline. There were no commits, pushes or deployments.
