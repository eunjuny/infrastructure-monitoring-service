import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.dsl.LockMode
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "3.5.16"
}

group = "dev.infrastructure"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(enforcedPlatform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.processTestResources {
    // SchemaIntegrationTest expects only this SQL fixture at the classpath root.
    from("../scripts/seed.sql")
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    testLogging {
        events("failed", "skipped")
    }
}

tasks.bootJar {
    archiveFileName = "monitoring-service.jar"
}

tasks.jar {
    enabled = false
}

tasks.wrapper {
    gradleVersion = "8.14.3"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"
    networkTimeout = 60_000
}

tasks.register("dependencySnapshot") {
    description = "Write resolved test/runtime module coordinates for migration audits."
    val destination = layout.buildDirectory.file("reports/dependency-coordinates.txt")
    outputs.file(destination)
    outputs.upToDateWhen { false }
    doLast {
        val coordinates = listOf(configurations.testCompileClasspath.get(), configurations.testRuntimeClasspath.get())
            .flatMap { it.incoming.resolutionResult.allComponents }.mapNotNull { component ->
                (component.id as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module}:${it.version}" }
            }.distinct().sorted()
        destination.get().asFile.apply {
            parentFile.mkdirs()
            writeText(coordinates.joinToString("\n", postfix = "\n"))
        }
    }
}
