plugins {
    alias(libs.plugins.agp.app)
}

val appVersion = "0.1.0"
val appVersionCode = providers.gradleProperty("moduleVersionCode").getOrElse("1").toInt()

val envKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val envKeystoreAlias = System.getenv("ANDROID_KEYSTORE_ALIAS")
val envKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val envKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")

android {
    namespace = "io.github.sfsmoon.animatuna"
    compileSdk = 37

    val releaseSigningConfig = if (
        !envKeystorePath.isNullOrBlank() &&
        !envKeystoreAlias.isNullOrBlank() &&
        !envKeystorePassword.isNullOrBlank() &&
        !envKeyPassword.isNullOrBlank() &&
        file(envKeystorePath).isFile
    ) {
        signingConfigs.create("release") {
            storeFile = file(envKeystorePath)
            storePassword = envKeystorePassword
            keyAlias = envKeystoreAlias
            keyPassword = envKeyPassword
        }
    } else {
        null
    }

    defaultConfig {
        applicationId = "io.github.sfsmoon.animatuna"
        // Pixel Launcher's transitions as Android 17 ships them.
        minSdk = 37
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            releaseSigningConfig?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    packaging {
        resources {
            // The Xposed framework discovers modern modules through these files.
            merges += "META-INF/xposed/*"
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "DebugProbesKt.bin")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("Animatuna-v$appVersion.apk")
        }
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    testImplementation(libs.junit)
}
