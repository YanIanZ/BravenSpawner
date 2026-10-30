# Historical evidence

These folders keep verification records only. The jars that used to live here were built
with `org.sqlite` relocated and fail at startup with
`UnsatisfiedLinkError: ...libs.sqlite.core.NativeDB._open_utf8`. They were removed.

Build a fresh jar with `./gradlew build` and deploy `build/libs/BravenSpawner.jar`.
`ShadedSqliteTest` opens SQLite through that shipped jar on every build.
