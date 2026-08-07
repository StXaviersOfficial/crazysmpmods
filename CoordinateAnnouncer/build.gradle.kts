plugins {
    `java-library`
}

// ── Coordinates ───────────────────────────────────────────────────────────────
group = "com.crazysmpmods"
version = "1.4.0"

// ── Java toolchain (auto-download JDK 25 via foojay resolver) ─────────────────
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

// ── Repositories ─────────────────────────────────────────────────────────────
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

// ── Dependencies ─────────────────────────────────────────────────────────────
dependencies {
    // Paper API for MC 1.21.11 (= Paper 26.2 build line).
    // Compile-only: provided by the server at runtime.
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

// ── Compile options ──────────────────────────────────────────────────────────
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

// ── Process plugin.yml with version ──────────────────────────────────────────
tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
        expand(props)
    }
}
