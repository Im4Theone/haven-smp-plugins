plugins {
    java
}

group = "net.kaloyan.smpcore"
version = "1.0.0"

// Version requirements chosen by the project owner: Minecraft 1.21.x on Java 21.
// 1.21.11 is the final release of the 1.21 line at the time of writing.
val paperApiVersion = "1.21.11-R0.1-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    // Provided by the server at runtime; nothing is shaded into the plugin jar.
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    // Test-only dependencies. Paper API is needed on the test classpath for Bukkit/Adventure/Brigadier types.
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.14.2")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
