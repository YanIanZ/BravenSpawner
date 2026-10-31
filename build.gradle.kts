plugins {
    java
    alias(libs.plugins.shadow)
}

group = "dev.yanianz"
version = "1.3.1-dev"

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.essentialsx.net/releases/")
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    maven("https://maven.enginehub.org/repo/")
    // Optional-at-runtime plugins. Only their API types are needed at compile
    // time; PlatformDetector and Placeholders guard the runtime link.
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.vault.api) { isTransitive = false }
    // Soft dependencies: present in the API surface, absent on most servers.
    // Non-transitive because only the interface/method signature is used.
    compileOnly(libs.floodgate.api) { isTransitive = false }
    compileOnly(libs.placeholderapi) { isTransitive = false }
    compileOnly(libs.essentialsx) { isTransitive = false }
    compileOnly(libs.shopgui.api) { isTransitive = false }
    compileOnly(libs.economyshopgui.api) { isTransitive = false }
    compileOnly(libs.modrinth.dky9btbd) { isTransitive = false }
    compileOnly(libs.worldedit.bukkit) { isTransitive = false }
    compileOnly(libs.worldedit.core) { isTransitive = false }
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testImplementation(libs.paper.api)
    testImplementation(libs.vault.api) { isTransitive = false }
    testRuntimeOnly(libs.junit.platform.launcher)
    implementation(libs.mongodb.driver.sync)
    implementation(libs.sqlite.jdbc)
    implementation(libs.hikaricp)
    implementation(libs.mysql.connector.j)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile> {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
}

tasks.shadowJar {
    archiveBaseName.set("BravenSpawner")
    archiveClassifier.set("")
    archiveVersion.set("")
    // Shadow 9 drops duplicate entries before transformers run unless they are included.
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    dependencies {
        // Paper ships SLF4J and the plugin class loader resolves it parent-first, so
        // HikariCP logs through the server's binding; a bundled copy would be dead weight.
        exclude(dependency("org.slf4j:.*:.*"))
    }
    minimize {
        // JDBC drivers are instantiated by DriverManager from a string, so the
        // shrinker cannot see the reference and would strip them.
        exclude(dependency("org.mongodb:.*:.*"))
        exclude(dependency("org.xerial:sqlite-jdbc:.*"))
        exclude(dependency("com.mysql:mysql-connector-j:.*"))
    }
    relocate("com.mongodb", "dev.yanianz.bravenspawner.libs.mongodb")
    relocate("org.bson", "dev.yanianz.bravenspawner.libs.bson")
    relocate("com.zaxxer.hikari", "dev.yanianz.bravenspawner.libs.hikari")
    // sqlite-jdbc loads its native library from a resource path derived from its own
    // package. Relocating the classes does not rename the .so resources, so the
    // lookup fails with UnsatisfiedLinkError at the first connection. The package
    // stays untouched; only the pool and the MySQL driver are relocated.
    relocate("com.mysql", "dev.yanianz.bravenspawner.libs.mysql")
    // Pulled in by mysql-connector-j; other plugins commonly bundle their own copy.
    relocate("com.google.protobuf", "dev.yanianz.bravenspawner.libs.protobuf")
}

tasks.build { dependsOn(tasks.shadowJar) }

tasks.test {
    useJUnitPlatform()
    // ShadedSqliteTest opens SQLite through the shipped jar to catch relocation breakage.
    dependsOn(tasks.shadowJar)
    val shaded = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(shaded)
    systemProperty("bravenspawner.shadedJar", shaded.get().asFile.absolutePath)
}
