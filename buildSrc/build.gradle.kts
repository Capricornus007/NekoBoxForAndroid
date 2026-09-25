plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

apply(from = "../repositories.gradle.kts")

dependencies {
    // Gradle Plugins
    // AGP declares KGP 2.2.10 as a runtime dependency; the explicit pin below is what actually
    // lands on the buildSrc classpath, so keep it pinned to stay unambiguous across AGP bumps.
    implementation("com.android.tools.build:gradle:9.4.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.10")
}
