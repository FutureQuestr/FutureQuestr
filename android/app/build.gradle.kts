plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun prop(name: String, def: String): String = (project.findProperty(name) as String?) ?: def

android {
    namespace = "com.futurequestr.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.futurequestr.app"
        minSdk = 26
        targetSdk = 34
        versionCode = prop("fq.versionCode", "10000").toInt()
        versionName = prop("fq.versionName", "1.0.0")
        buildConfigField("String", "CLIENT_ID", "\"" + prop("fq.clientId", "") + "\"")
        buildConfigField("String", "WORKER_URL", "\"" + prop("fq.workerUrl", "") + "\"")
        buildConfigField("String", "RELEASES_REPO", "\"" + prop("fq.releasesRepo", "") + "\"")
    }

    signingConfigs {
        create("release") {
            val path = System.getenv("FQ_KS_PATH")
            if (path != null && path.isNotEmpty()) {
                storeFile = file(path)
                storePassword = System.getenv("FQ_KS_PASS")
                keyAlias = System.getenv("FQ_KEY_ALIAS")
                keyPassword = System.getenv("FQ_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
