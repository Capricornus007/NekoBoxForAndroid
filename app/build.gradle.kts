@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    namespace = "io.nekohasekai.sagernet"
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
}

ksp {
    arg("room.incremental", "true")
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(fileTree("libs"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.fragment:fragment:1.9.1")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    // WorkManager 2.12 requires minSdk 24; this app supports API 23.
    //noinspection GradleDependency
    implementation("androidx.work:work-runtime:2.11.2")
    //noinspection GradleDependency
    implementation("androidx.work:work-multiprocess:2.11.2")

    implementation("com.google.android.material:material:1.14.0")
    implementation("com.google.code.gson:gson:2.14.0")

    // Bundle the scanner model for offline use without Google Play Services.
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // ZXing generates QR codes; ML Kit only scans them.
    implementation("com.google.zxing:core:3.5.4")

    implementation(platform("io.github.rosemoe:editor-bom:0.24.6"))
    implementation("io.github.rosemoe:editor")
    implementation("io.github.rosemoe:language-textmate")
    // TextMate exposes JDT nullability annotations in its public signatures.
    compileOnly("org.eclipse.jdt:org.eclipse.jdt.annotation:2.4.100")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.yaml:snakeyaml:2.7")
    implementation("com.jakewharton:process-phoenix:3.0.0")
    implementation("com.esotericsoftware:kryo:5.6.2")
    implementation("me.zhanghai.android.fastscroll:library:1.3.0")

    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.17")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.5")

    constraints {
        // room-migration 2.8.5 ships serializers compiled against kotlinx-serialization
        // 1.8.1 (JVM default methods). The transitive core from savedstate/lifecycle is
        // older and lands in the app APK, so the test APK's copy is dropped and the
        // migration tests die with AbstractMethodError unless the app resolves 1.8.1 too.
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
    }

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
