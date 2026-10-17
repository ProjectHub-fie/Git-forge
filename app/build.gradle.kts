import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.projecthub.gitforge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.projecthub.gitforge"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Where the in-app updater looks for new versions. A build variant can point
        // at a different repository with -PGITFORGE_UPDATE_REPO_OWNER=... so a fork's
        // APK updates from the fork's own releases rather than upstream's.
        buildConfigField(
            "String",
            "UPDATE_REPO_OWNER",
            "\"${findProperty("GITFORGE_UPDATE_REPO_OWNER") ?: "ProjectHub-fie"}\"",
        )
        buildConfigField(
            "String",
            "UPDATE_REPO_NAME",
            "\"${findProperty("GITFORGE_UPDATE_REPO_NAME") ?: "Git-forge"}\"",
        )

        // Method A (the GitHub device flow) needs a public OAuth App client ID. A
        // client ID is not a secret: the device flow exists precisely so that no
        // client secret has to be shipped. It can be overridden per build with
        // -PGITFORGE_OAUTH_CLIENT_ID=..., and at runtime in Settings, so a fork does
        // not have to carry this value.
        buildConfigField(
            "String",
            "DEFAULT_OAUTH_CLIENT_ID",
            "\"${findProperty("GITFORGE_OAUTH_CLIENT_ID") ?: "Iv23li8KI2OKDHaDX4BN"}\"",
        )

        ndk {
            // The Termux userspace packages GitForge installs on demand are built
            // for arm64-v8a only. Nothing native is bundled in the APK, so this only
            // declares which devices the app is offered to: offering armeabi-v7a or
            // x86_64 would install an app whose runtimes can never run there.
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Release signing comes from environment variables so no secret ever lands in
    // the repository. Without them the release build is produced unsigned.
    signingConfigs {
        create("release") {
            val storePath = System.getenv("GITFORGE_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("GITFORGE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GITFORGE_KEY_ALIAS")
                keyPassword = System.getenv("GITFORGE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            // JGit and kotlinx.serialization both ship the metadata R8 needs, but
            // shrinking is left off so a release build behaves exactly like debug.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (!System.getenv("GITFORGE_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // AndroidX core / lifecycle / compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Structured local metadata + preferences
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Credential protection (Android Keystore backed)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Deferrable background work
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Networking + serialization for the GitHub REST API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Git engine. JGit is pure Java, so it needs no NDK and no native git binary,
    // and it is the single writer of every index and working tree in the app.
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.7.0.202309050840-r")
    implementation("org.slf4j:slf4j-android:1.7.36")

    // Reads the Termux .deb packages (ar + tar + xz) that supply node, npm and
    // python3 on demand. Both are pure Java.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Local unit tests (JVM)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.room:room-testing:2.6.1")

    // Instrumented tests
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
