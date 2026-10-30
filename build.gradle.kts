plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.yanianz"
version = "1.1.4"

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
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    // Soft dependencies: present in the API surface, absent on most servers.
    // Non-transitive because only the interface/method signature is used.
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT") { isTransitive = false }
    compileOnly("me.clip:placeholderapi:2.12.3") { isTransitive = false }
    compileOnly("net.essentialsx:EssentialsX:2.21.2") { isTransitive = false }
    compileOnly("com.github.brcdev-minecraft:shopgui-api:3.2.0") { isTransitive = false }
    compileOnly("com.github.Gypopo:EconomyShopGUI-API:1.11.0") { isTransitive = false }
    compileOnly("maven.modrinth:DKY9btbd:J66QOTLZ") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.9") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.9") { isTransitive = false }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("org.mongodb:mongodb-driver-sync:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.mysql:mysql-connector-j:9.1.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile> {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.processResources {
    inputs.property("pluginVersion", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.shadowJar {
    archiveBaseName.set("BravenSpawner")
    archiveClassifier.set("")
    archiveVersion.set("")
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
}

tasks.build { dependsOn(tasks.shadowJar) }

tasks.test { useJUnitPlatform() }
