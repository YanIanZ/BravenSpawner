plugins {
    java
}

group = "dev.yanianz"
version = "1.5.0"

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.essentialsx.net/releases/")
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    maven("https://repo.bg-software.com/repository/api/") { content { includeGroup("com.bgsoftware") } }
    maven("https://maven.enginehub.org/repo/")
    // Optional-at-runtime plugins. Only their API types are needed at compile
    // time; PlatformDetector and Placeholders guard the runtime link.
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.extendedclip.com/releases/")
    // Custom-item plugins, each repository limited to its own group.
    maven("https://nexus.phoenixdevt.fr/repository/maven-public/") {
        content { includeModule("net.Indyuce", "MMOItems-API"); includeModule("io.lumine", "MythicLib-dist") }
    }
    maven("https://repo.auxilor.io/repository/maven-public/") { content { includeModule("com.willfp", "eco") } }
    maven("https://repo.oraxen.com/releases") { content { includeModule("io.th0rgal", "oraxen") } }
    maven("https://repo.nexomc.com/releases") { content { includeModule("com.nexomc", "nexo") } }
    maven("https://mvn.lumine.io/repository/maven-public/") { content { includeModule("io.lumine", "Mythic-Dist") } }
}

val runtimeLibraries = listOf(libs.mongodb.driver.sync, libs.sqlite.jdbc, libs.hikaricp, libs.mysql.connector.j)

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
    compileOnly(libs.superiorskyblock.api) { isTransitive = false }
    // Custom-item plugins: called directly from item/ adapters that load only after
    // the plugin is confirmed enabled. Never shaded.
    compileOnly(libs.mmoitems.api) { isTransitive = false }
    compileOnly(libs.mythiclib) { isTransitive = false }
    compileOnly(libs.eco) { isTransitive = false }
    compileOnly(libs.itemsadder.api) { isTransitive = false }
    compileOnly(libs.oraxen) { isTransitive = false }
    compileOnly(libs.nexo) { isTransitive = false }
    compileOnly(libs.mythicmobs) { isTransitive = false }
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testImplementation(libs.paper.api)
    testImplementation(libs.vault.api) { isTransitive = false }
    testImplementation(libs.superiorskyblock.api) { isTransitive = false }
    testRuntimeOnly(libs.junit.platform.launcher)
    // Runtime libraries are not bundled: plugin.yml `libraries:` makes the server
    // download them from Maven Central on first start (Spigot/Paper library loader).
    // The versions below are written into plugin.yml by processResources.
    for (library in runtimeLibraries) {
        compileOnly(library)
        testImplementation(library)
    }
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
    val libraries = runtimeLibraries.map { provider ->
        val dependency = provider.get()
        "${dependency.module.group}:${dependency.module.name}:${dependency.versionConstraint.requiredVersion}"
    }
    inputs.property("pluginVersion", pluginVersion)
    inputs.property("libraries", libraries)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion, "libraries" to libraries.joinToString("\n") { "  - $it" })
    }
}

tasks.jar {
    archiveBaseName.set("BravenSpawner")
    archiveVersion.set("")
}

tasks.test {
    useJUnitPlatform()
    // PluginJarTest checks the shipped jar: libraries declared, none bundled.
    dependsOn(tasks.jar)
    val pluginJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(pluginJar)
    systemProperty("bravenspawner.pluginJar", pluginJar.get().asFile.absolutePath)
}
