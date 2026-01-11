plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
    id("kotlin-parcelize")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.vulpinenotes"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.vulpinenotes"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.1 Beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE", "META-INF/LICENSE-FIREBASE.txt", "META-INF/NOTICE")
            excludes += setOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES",

                "META-INF/LICENSE-LGPL-2.1.txt",
                "META-INF/LICENSE-LGPL-3.txt",
                "META-INF/LICENSE-APACHE-2.0.txt",

                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
                "META-INF/*.version",
                "**/module-info.class"
            )
        }
    }
}

dependencies {
    // Firebase BOM (управляет всеми версиями Firebase)
    implementation(platform("com.google.firebase:firebase-bom:34.5.0"))

    // Основные Firebase модули (без -ktx — KTX встроено)
    implementation("com.google.firebase:firebase-analytics")  // Аналитика (без -ktx)
    implementation("com.google.firebase:firebase-firestore")  // Firestore (основной модуль)
    implementation("com.google.firebase:firebase-auth")       // Auth (основной)
    implementation("com.google.firebase:firebase-storage")    // Storage (если нужно для обложек)
    implementation("com.google.firebase:firebase-firestore-ktx:24.10.3")
    implementation("com.google.firebase:firebase-database")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Остальные зависимости (не Firebase)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation("com.google.android.material:material:1.12.0")
    implementation("de.hdodenhof:circleimageview:3.1.0")

    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.6")

    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation(libs.androidx.animation.core)
    implementation(libs.androidx.material3)
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Google Sign-In (не Firebase, остаётся)
    implementation("com.google.android.gms:play-services-auth:21.1.0")

    // Glide для аватарок
    implementation("com.github.bumptech.glide:glide:4.16.0")

    implementation ("androidx.room:room-runtime:2.6.1")
    implementation ("androidx.room:room-ktx:2.6.1")
    kapt ("androidx.room:room-compiler:2.6.1")

    // Для работы с Coroutines
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Тесты
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Markdown
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:editor:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")

    implementation("io.noties.markwon:ext-tables:4.6.2")

    // Для экспорта в PDF (iText)
    implementation("com.itextpdf:itext7-core:8.0.5")
    implementation("com.itextpdf:html2pdf:5.0.5")
    // Для работы с HTML (JSoup)
    implementation("org.jsoup:jsoup:1.17.2")
}