plugins {
    java
}

group = "net.kaloyan.smpeconomy"
version = "1.0.0"

// Same targets as SMP-Core: Minecraft 1.21.11 on Java 21.
val paperApiVersion = "1.21.11-R0.1-SNAPSHOT"
// Paper 1.21.11 ships this exact sqlite-jdbc on the server class path (see README, "Dependencies").
val sqliteJdbcVersion = "3.49.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

// Example consumer plugin; compiled by `check` so the documented API usage cannot rot.
val example: SourceSet = sourceSets.create("example") {
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
}

dependencies {
    // Provided by the server at runtime; nothing is shaded into the plugin jar.
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    // Required plugin dependency (plugin.yml "depend"). Never shaded.
    compileOnly(files("libs/SMP-Core-1.0.0.jar"))
    // Paper's server class path already provides the driver; see README for why it is not bundled.
    compileOnly("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")

    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation(example.output)
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

tasks.check {
    dependsOn(tasks.named("compileExampleJava"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
