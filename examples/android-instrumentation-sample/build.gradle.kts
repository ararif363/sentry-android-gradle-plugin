plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    id("io.sentry.android.gradle")
}

// useful for local debugging of the androidx.sqlite lib
// make sure to checkout the lib from https://github.com/androidx/androidx/tree/androidx-main/sqlite/sqlite-framework
// configurations.all {
//    resolutionStrategy.dependencySubstitution {
//        substitute(module("androidx.sqlite:sqlite-framework")).using(project(":sqlite-framework"))
//    }
// }

android {
    compileSdk = 30
    defaultConfig {
        minSdk = 21
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles.add(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
    }
}

// useful, when we want to modify room-generated classes, and then compile them into .class files
// so room does not re-generate and overwrite our changes
// afterEvaluate {
//    tasks.getByName("kaptDebugKotlin").enabled = false
// }

dependencies {
    implementation(Samples.AndroidX.recyclerView)
    implementation(Samples.AndroidX.lifecycle)
    implementation(Samples.AndroidX.appcompat)

    implementation(Samples.Coroutines.core)
    implementation(Samples.Coroutines.android)

    implementation(Samples.Room.runtime)
    implementation(Samples.Room.ktx)
    implementation(Samples.Room.rxjava)

    implementation(Samples.Timber.timber)
    implementation(Samples.Fragment.fragmentKtx)
    implementation(project(":examples:android-room-lib"))

    kapt(Samples.Room.compiler)
}

sentry {
    autoUploadProguardMapping.set(false)

    tracingInstrumentation {
        forceInstrumentDependencies.set(true)
    }
}
