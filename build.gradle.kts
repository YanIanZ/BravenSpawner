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
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.224-stable")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("com.mysql:mysql-connector-j:26.10.0")
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
    relocate("com.zaxxer.hikari", "dev.yanianz.bravenspawner.libs.hikari")
    relocate("org.sqlite", "dev.yanianz.bravenspawner.libs.sqlite")
    relocate("com.mysql", "dev.yanianz.bravenspawner.libs.mysql")
}

tasks.build { dependsOn(tasks.shadowJar) }
