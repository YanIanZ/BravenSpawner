# Continuation checkpoint

Goal: recreate all observable VirtualSpawner7.2.0-rc-12 features independently.
Completed native pass: configured virtual production/storage,40 mob defaults,
menus/purchases/puzzle/audit, wand modifiers/lifetimes, typed boosters, scheduled
events, restrictions,3 real price APIs,WorldGuard and Mongo Braven schema v1.
Validation: clean Gradle build,77 tests pass,shaded JAR checked.
Changed source: bootstrap/settings/manager/model/listeners/commands/selling;
new product/menu/wand/audit/events/restrictions/commerce/protection/Mongo modules;
Folia helpers/holograms/hoppers/Ninja/restoration; resources and tests.
See FEATURE_PARITY.md for exact evidence and outstanding gaps.

Next: reconcile this verified snapshot with the shared checkout once its other
OpenCode session is paused. Do not overwrite either session's unreviewed changes.
Then qualify on disposable Paper/Folia servers and actual provider versions;
finish remaining shop/currency/land/API/legacy-version/reference-data parity.
No production server or shared Mongo storage was accessed during this work.
