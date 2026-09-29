// Root build: plugin versions and repository-wide formatting. Dependency repositories live in
// settings.gradle.kts; app configuration lives in app/build.gradle.kts and buildSrc/Helpers.kt.
plugins {
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("com.diffplug.spotless") version "8.10.3"
}

// Spotless applies a base plugin that already provides a `clean` task, so only register ours
// if it isn't present (avoids "task with that name already exists").
if (tasks.findByName("clean") == null) {
    tasks.register<Delete>("clean") {
        delete(rootProject.layout.buildDirectory)
    }
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint("1.8.0")
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint("1.8.0")
    }
}
