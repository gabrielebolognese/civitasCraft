// NOTE for the agent: if the paper-api version below fails to resolve, check
// https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/
// and update it to the current 1.21.x snapshot. Do the same for the plugin versions.

plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.civitas"
version = "0.1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // shaded into the jar
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("it.unimi.dsi:fastutil-core:8.5.14")
    implementation("org.spongepowered:configurate-yaml:4.1.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("com.github.seeseemelk:MockBukkit-v1.21:3.9.0")
}

tasks {
    test { useJUnitPlatform() }

    shadowJar {
        archiveClassifier.set("")
        // relocate shaded deps so they never clash with other plugins on the server
        relocate("com.zaxxer.hikari", "dev.civitas.lib.hikari")
        relocate("it.unimi.dsi.fastutil", "dev.civitas.lib.fastutil")
        relocate("org.spongepowered.configurate", "dev.civitas.lib.configurate")
        minimize()
    }

    build { dependsOn(shadowJar) }

    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }
}
