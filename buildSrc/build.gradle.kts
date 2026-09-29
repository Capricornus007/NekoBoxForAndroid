plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

// buildSrc is a separate Gradle build, so the root settings.gradle.kts repositories do not apply.
repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.android.tools.build:gradle:9.4.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
}
