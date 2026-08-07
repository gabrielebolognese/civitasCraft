// NOTE for the agent: if the paper-api version below fails to resolve, check
// https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/
// and update it to the current 1.21.x snapshot. Do the same for the plugin versions.

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.civitas"
version = "0.1.0"

val paperApiVersion = "1.21.11-R0.1-SNAPSHOT"
val pluginVersion = version.toString()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    // SPEC 20 decision 7: PlaceholderAPI and an optional Vault economy provider.
    maven("https://repo.extendedclip.com/releases")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    // Soft dependencies. Both are compileOnly and guarded by a runtime presence check, so
    // the plugin works on a server that has neither installed.
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }

    // shaded into the jar
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    implementation("com.mysql:mysql-connector-j:9.7.0")
    implementation("it.unimi.dsi:fastutil-core:8.5.14")
    implementation("org.spongepowered:configurate-yaml:4.2.0")

    // The server API is compileOnly at runtime but tests need the real classes
    // (YamlConfiguration, MiniMessage, Component) on their classpath.
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.110.0")
}

tasks {
    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
        // Gradle does not pass the invoking JVM's system properties to the test JVM, so
        // without this the MySQL dialect tests would silently skip even when a server was
        // named on the command line — the worst outcome, since the run still goes green.
        // See StorageTestSupport for the properties and what they must point at.
        for (property in listOf("civitas.test.mysql.url", "civitas.test.mysql.user",
                "civitas.test.mysql.password", "civitas.test.dialect")) {
            System.getProperty(property)?.let { systemProperty(property, it) }
        }
    }

    shadowJar {
        archiveClassifier.set("")
        // relocate shaded deps so they never clash with other plugins on the server
        relocate("com.zaxxer.hikari", "dev.civitas.lib.hikari")
        relocate("it.unimi.dsi.fastutil", "dev.civitas.lib.fastutil")
        relocate("org.spongepowered.configurate", "dev.civitas.lib.configurate")
        // The JDBC drivers are deliberately NOT relocated: both resolve their driver
        // class, and sqlite its native library, by hardcoded package name. Paper gives
        // every plugin its own class loader, so an unrelocated driver cannot clash with
        // another plugin's copy.
        minimize {
            exclude(dependency("org.xerial:sqlite-jdbc:.*"))
            exclude(dependency("com.mysql:mysql-connector-j:.*"))
            exclude(dependency("com.zaxxer:HikariCP:.*"))
        }
    }

    build { dependsOn(shadowJar) }

    processResources {
        // Captured at configuration time: reading project.version inside the
        // filesMatching action would touch Task.project at execution time, which
        // Gradle 10 rejects and the configuration cache cannot support.
        val tokens = mapOf("version" to pluginVersion)
        inputs.properties(tokens)
        filesMatching("plugin.yml") {
            expand(tokens)
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf("-parameters", "-Xlint:all,-serial,-processing", "-Werror")
        )
    }
}
