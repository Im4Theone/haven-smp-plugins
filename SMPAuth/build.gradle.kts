plugins {
    java
}

group = "net.kaloyan.smpauth"
version = "1.0.0"

val paperApiVersion = "1.21.11-R0.1-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    // Paper provides log4j; it is only needed to hide passwords from the console log.
    compileOnly(files("libs/log4j-core-2.25.2.jar", "libs/log4j-api-2.25.2.jar"))
    // Provided by the server; the login hook is written against the same version.
    compileOnly("io.netty:netty-transport:4.2.7.Final")
    compileOnly("io.netty:netty-common:4.2.7.Final")

    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation(files("libs/log4j-core-2.25.2.jar", "libs/log4j-api-2.25.2.jar"))
    testImplementation("io.netty:netty-transport:4.2.7.Final")
    testImplementation("io.netty:netty-common:4.2.7.Final")
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
