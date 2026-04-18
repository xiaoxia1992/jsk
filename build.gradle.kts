plugins {
    kotlin("jvm") version "1.9.23" apply false
}

allprojects {
    group = "io.kjs"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(17)
        }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            kotlinOptions {
                freeCompilerArgs = listOf("-Xjsr305=strict", "-Xcontext-receivers")
            }
        }
    }
}
