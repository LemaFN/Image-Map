plugins {
    java
}

group = "de.imagemapclaude"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperVersion")}")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25)) // Paper 26.1+ läuft auf Java 25
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
    // Dieselbe Webseite wie auf GitHub Pages liegt zusätzlich im Plugin (Fallback ohne HTTPS)
    from("docs/index.html") { into("web") }
}

tasks.jar {
    archiveFileName.set("ImageMap-Claude-${project.version}.jar")
}
