plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.yanianz"
version = "1.0.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
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
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.shadowJar {
    archiveBaseName.set("BravenSpawner")
    archiveClassifier.set("")
    archiveVersion.set("")
    minimize {
        // JDBC drivers are instantiated by DriverManager from a string, so the
        // shrinker cannot see the reference and would strip them.
        exclude(dependency("org.xerial:sqlite-jdbc:.*"))
        exclude(dependency("com.mysql:mysql-connector-j:.*"))
    }
    relocate("com.zaxxer.hikari", "dev.yanianz.bravenspawner.libs.hikari")
    relocate("org.sqlite", "dev.yanianz.bravenspawner.libs.sqlite")
    relocate("com.mysql", "dev.yanianz.bravenspawner.libs.mysql")
}

tasks.build { dependsOn(tasks.shadowJar) }

tasks.test { useJUnitPlatform() }
