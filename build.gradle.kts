plugins {
    kotlin("jvm") version "2.0.21"
}

group = "com.sphericalchickens"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("io.ktor:ktor-client-cio:2.3.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1") // Replace with the latest version
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.1") // Replace with the latest version


    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}