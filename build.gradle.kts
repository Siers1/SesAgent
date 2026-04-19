plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "com.siersi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // CLI 交互
    implementation("com.github.ajalt.clikt:clikt:5.1.0")

    // LangChain4j
    implementation("dev.langchain4j:langchain4j:0.36.0")
    implementation("dev.langchain4j:langchain4j-open-ai:0.36.0")

    // 协程 & 序列化
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // 日志
    implementation("org.slf4j:slf4j-simple:2.0.16")
}

application {
    mainClass.set("com.siersi.sesagent.AgentMainKt")
}

tasks.shadowJar {
    archiveFileName.set("sesagent.jar")
    mergeServiceFiles()       // 关键：合并 SPI，确保 LangChain4j 正常工作
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks {
    named<Jar>("jar") {
        enabled = false
    }

    listOf("startScripts", "distZip", "distTar").forEach { taskName ->
        named(taskName) {
            enabled = false
        }
    }

    named("assemble") {
        dependsOn(named("shadowJar"))
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll("-Xcontext-parameters")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
