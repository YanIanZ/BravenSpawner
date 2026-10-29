plugins {
    java
    id("com.gradleup.shadow") version "9.0.0"
}

group = "dev.yanianz"
version = "1.0.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.mysql:mysql-connector-j:8.4.0")
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
    minimize()
    relocate("com.zaxxer.hikari", "dev.yanianz.bravemspawner.libs.hikari")
    relocate("org.sqlite", "dev.yanianz.bravemspawner.libs.sqlite")
    relocate("com.mysql", "dev.yanianz.bravemspawner.libs.mysql")
}

tasks.build { dependsOn(tasks.shadowJar) }
