import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
}

// Cargar propiedades del keystore desde keystore.properties (si existe)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.example.rutaalmacen"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.rutaalmacen"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file("../keystore/rutaalmacen_release.jks")
                storePassword = keystoreProperties.getProperty("KEYSTORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("KEY_ALIAS")
                keyPassword = keystoreProperties.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    lint {
        checkReleaseBuilds = false
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.play.services.billing)
    implementation(libs.firebase.functions.ktx)
    implementation(libs.coroutines.play.services)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.storage.ktx)
    implementation(libs.play.services.auth)
    implementation(libs.play.services.location)
    implementation(libs.coroutines.android)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.gson)
    implementation(libs.androidx.exifinterface)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.glide)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.drawerlayout)
    implementation("com.google.android.gms:play-services-ads:23.0.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Tarea para parchar p_align de los .so files (fix Android 16KB warning)
// (Desactivada: useLegacyPackaging=true evita el aviso de otra forma)
// val patch16KBAlignment by tasks.registering(Exec::class) {
//     group = "build"
//     description = "Parcha los .so del APK debug para eliminar el aviso Android 16 KB Alignment"
//     val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
//     val output = layout.buildDirectory.file("outputs/apk/debug/app-debug-16kb-fixed.apk")
//     val script = rootProject.file("patch-so-alignment.ps1")
//     inputs.file(apk); inputs.file(script); outputs.file(output)
//     commandLine = listOf(
//         "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
//         "-File", script.absolutePath,
//         "-ApkPath", apk.get().asFile.absolutePath,
//         "-OutputPath", output.get().asFile.absolutePath,
//     )
// }
// afterEvaluate { tasks.findByName("assembleDebug")?.finalizedBy("patch16KBAlignment") }
