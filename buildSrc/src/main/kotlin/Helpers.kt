import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.AbstractAppExtension
import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.getByName
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import java.util.Base64
import java.util.Properties
import kotlin.system.exitProcess

private val Project.android get() = extensions.getByName<ApplicationExtension>("android")

private lateinit var metadata: Properties
private lateinit var localProperties: Properties

fun Project.requireMetadata(): Properties {
    if (!::metadata.isInitialized) {
        metadata = Properties().apply {
            load(rootProject.file("nb4a.properties").inputStream())
        }
    }
    return metadata
}

fun Project.requireLocalProperties(): Properties {
    if (!::localProperties.isInitialized) {
        localProperties = Properties()

        val base64 = System.getenv("LOCAL_PROPERTIES")
        if (!base64.isNullOrBlank()) {

            localProperties.load(Base64.getDecoder().decode(base64).inputStream())
        } else if (project.rootProject.file("local.properties").exists()) {
            localProperties.load(rootProject.file("local.properties").inputStream())
        }
    }
    return localProperties
}

fun Project.setupCommon() {
    android.apply {
        buildToolsVersion = "35.0.1"
        compileSdk = 35
        defaultConfig {
            minSdk = 23
            targetSdk = 35
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = true
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        (android as ExtensionAware).extensions.getByName<KotlinJvmOptions>("kotlinOptions").apply {
            jvmTarget = JavaVersion.VERSION_1_8.toString()
        }
        lint {
            showAll = true
            checkAllWarnings = true
            checkReleaseBuilds = true
            warningsAsErrors = true
            textOutput = project.file("build/lint.txt")
            htmlOutput = project.file("build/lint.html")
        }
        packaging {
            resources.excludes.addAll(
                listOf(
                    "**/*.kotlin_*",
                    "/META-INF/*.version",
                    "/META-INF/native/**",
                    "/META-INF/native-image/**",
                    "/META-INF/INDEX.LIST",
                    "DebugProbesKt.bin",
                    "com/**",
                    "org/**",
                    "**/*.java",
                    "**/*.proto",
                    "okhttp3/**"
                )
            )
        }
        (this as? AbstractAppExtension)?.apply {
            buildTypes {
                getByName("release") {
                    isShrinkResources = true
                    if (System.getenv("nkmr_minify") == "0") {
                        isShrinkResources = false
                        isMinifyEnabled = false
                    }
                }
                getByName("debug") {
                    applicationIdSuffix = null
                    debuggable(true)
                    jniDebuggable(true)
                }
            }
        }
    }
}

fun Project.setupAppCommon() {
    setupCommon()

    val lp = requireLocalProperties()
    val keystorePwd = (lp.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS"))
        ?.takeIf { it.isNotBlank() }
    val alias = (lp.getProperty("ALIAS_NAME") ?: System.getenv("ALIAS_NAME"))
        ?.takeIf { it.isNotBlank() }
    val pwd = (lp.getProperty("ALIAS_PASS") ?: System.getenv("ALIAS_PASS"))
        ?.takeIf { it.isNotBlank() }
    val releaseKeystoreFile = rootProject.file("release.keystore")
    val debugKeystoreFile = rootProject.file("app/debug.keystore")

    android.apply {
        signingConfigs {
            if (keystorePwd != null && alias != null && pwd != null && releaseKeystoreFile.isFile) {
                create("release") {
                    storeFile = releaseKeystoreFile
                    storePassword = keystorePwd
                    keyAlias = alias
                    keyPassword = pwd
                }
            }
            if (debugKeystoreFile.isFile) {
                create("ciDebug") {
                    storeFile = debugKeystoreFile
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
        buildTypes {
            val releaseKey = signingConfigs.findByName("release")
            val ciDebugKey = signingConfigs.findByName("ciDebug")
            when {
                releaseKey != null -> {
                    getByName("release").signingConfig = releaseKey
                    getByName("debug").signingConfig = releaseKey
                }

                ciDebugKey != null -> {
                    getByName("release").signingConfig = ciDebugKey
                    getByName("debug").signingConfig = ciDebugKey
                }
            }
        }
    }
}

fun Project.requireReleaseSignature() {
    tasks.configureEach {
        if (name.startsWith("package") && name.endsWith("Release")) {
            doFirst {
                check(android.buildTypes.getByName("release").signingConfig != null) {
                    "Signing is required. Configure a private key or restore app/debug.keystore."
                }
            }
        }
    }
}

fun Project.setupApp() {
    val pkgName = requireMetadata().getProperty("PACKAGE_NAME")
    val verName = requireMetadata().getProperty("VERSION_NAME")
    val sourceCommit = System.getenv("GITHUB_SHA") ?: runCatching {
        providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
    }.getOrDefault("unknown")
    val verCode = (requireMetadata().getProperty("VERSION_CODE").toInt()) * 5
    android.apply {
        defaultConfig {
            applicationId = pkgName
            buildConfigField("String", "SOURCE_COMMIT", "\"$sourceCommit\"")
            versionCode = verCode
            versionName = verName
            buildConfigField("String", "PRE_VERSION_NAME", "\"\"")
        }
    }
    setupAppCommon()
    requireReleaseSignature()

    android.apply {
        this as AbstractAppExtension

        buildTypes {
            getByName("release") {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    file("proguard-rules.pro")
                )
            }
        }

        splits.abi {
            reset()
            isEnable = true
            isUniversalApk = false
            include("armeabi-v7a")
            include("arm64-v8a")
            include("x86")
            include("x86_64")
        }

        flavorDimensions += "vendor"
        productFlavors {
            create("oss")
            create("fdroid")
            create("play")
            create("preview") {
                buildConfigField(
                    "String",
                    "PRE_VERSION_NAME",
                    "\"${requireMetadata().getProperty("PRE_VERSION_NAME")}\""
                )
            }
        }

        val abiPattern = Regex("(armeabi-v7a|arm64-v8a|x86_64|x86)")

        applicationVariants.all {
            outputs.all {
                this as BaseVariantOutputImpl
                val abi = abiPattern.find(outputFileName)?.value ?: "universal"
                outputFileName = "NB_SF-$verName-$abi.apk"
            }
        }

        for (abi in listOf("Arm64", "Arm", "X64", "X86")) {
            tasks.create("assemble" + abi + "FdroidRelease") {
                dependsOn("assembleFdroidRelease")
            }
        }

        sourceSets.getByName("main").apply {
            jniLibs.srcDir("executableSo")
        }
    }
}
