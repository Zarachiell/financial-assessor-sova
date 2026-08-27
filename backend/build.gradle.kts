import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    application
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    id("io.ktor.plugin") version "3.5.2"
}

val geminiSdkVersion = "0.5.0"
val geminiSdkRuntime = configurations.create("geminiSdkRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    resolutionStrategy.eachDependency {
        if (requested.group == "io.ktor") {
            useVersion("2.3.8")
            because("The official Gemini Kotlin SDK 0.5.0 is binary-compatible with Ktor 2.3.8")
        }
    }
}

val shadedGeminiSdk = tasks.register<ShadowJar>("shadedGeminiSdk") {
    group = "build"
    description = "Isolates the Gemini Kotlin SDK Ktor 2 runtime from the Ktor 3 application runtime."
    archiveBaseName.set("google-genai-kotlin-isolated")
    archiveClassifier.set("")
    archiveVersion.set(geminiSdkVersion)
    destinationDirectory.set(layout.buildDirectory.dir("gemini-sdk"))
    configurations = listOf(geminiSdkRuntime)
    dependencies {
        include(dependency("com.google.genai:google-genai-kotlin-jvm:$geminiSdkVersion"))
        include(dependency("io.ktor:.*"))
    }
    relocate("io.ktor", "com.sova.internal.gemini.ktor")
    mergeServiceFiles()
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

    compileOnly("com.google.genai:google-genai-kotlin-jvm:$geminiSdkVersion") {
        isTransitive = false
    }
    testCompileOnly("com.google.genai:google-genai-kotlin-jvm:$geminiSdkVersion") {
        isTransitive = false
    }
    runtimeOnly(files(shadedGeminiSdk))
    testRuntimeOnly(files(shadedGeminiSdk))
    implementation("com.google.auth:google-auth-library-oauth2-http:1.33.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    geminiSdkRuntime("com.google.genai:google-genai-kotlin-jvm:$geminiSdkVersion")

    implementation("com.twilio.sdk:twilio:12.1.1")

    implementation("org.flywaydb:flyway-core:13.0.0")
    implementation("org.flywaydb:flyway-database-postgresql:13.0.0")
    implementation("org.jooq:jooq:3.21.7")
    runtimeOnly("org.postgresql:postgresql:42.7.13")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.38")
    runtimeOnly("net.logstash.logback:logstash-logback-encoder:8.1")

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-mock")
    testImplementation("ch.qos.logback:logback-classic:1.5.38")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.3.0"))
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
