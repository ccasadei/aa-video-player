plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.casadei.aavideoscreensaver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.casadei.aavideoscreensaver"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Android Auto Car App Library
    implementation(libs.car.app.projected)
    testImplementation(libs.car.app.testing)
    testImplementation(libs.junit)
    // org.json reale per i test JVM (quello di android.jar è solo uno stub)
    testImplementation(libs.orgjson)

    // Media3 ExoPlayer (MP4, MKV, WebM, HLS .m3u8, DASH, OkHttp DataSource, Video Effects)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.common)
    implementation(libs.media3.effect)
    // AspectRatioFrameLayout: proporzioni del video sulla superficie di Android Auto (VirtualDisplay + Presentation)
    implementation(libs.media3.ui)

    // Network, YouTube & StreamingCommunity Extraction (NewPipeExtractor + OkHttp + Jsoup)
    implementation(libs.okhttp)
    implementation(libs.newpipe.extractor)
    implementation("org.jsoup:jsoup:1.17.2")
}
