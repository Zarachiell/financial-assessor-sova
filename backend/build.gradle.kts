plugins {
    application
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    id("io.ktor.plugin") version "3.5.2"
}

group = "com.sova"
version = "0.1.0"

application {
    mainClass.set("com.sova.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

ktor {
    fatJar {
        archiveFileName.set("sova-api.jar")
    }
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-call-id")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-server-forwarded-header")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-serialization-kotlinx-json")

    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")

    implementation("com.twilio.sdk:twilio:12.1.1")

    implementation("org.flywaydb:flyway-core:13.0.0")
    implementation("org.flywaydb:flyway-database-postgresql:13.0.0")
    implementation("org.jooq:jooq:3.21.7")
    runtimeOnly("org.postgresql:postgresql:42.7.13")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.38")
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.1")

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-mock")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.3.0"))
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
