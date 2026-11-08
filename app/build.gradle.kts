plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.cy.xposedshot"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cy.xposedshot"
        minSdk = 26
        targetSdk = 37
        versionCode = 23
        versionName = "1.8.2"
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore.jks")
            storePassword = System.getenv("STORE_PASSWORD") ?: throw GradleException("STORE_PASSWORD环境变量未设置")
            keyAlias = System.getenv("KEY_ALIAS") ?: throw GradleException("KEY_ALIAS环境变量未设置")
            keyPassword = System.getenv("KEY_PASSWORD") ?: throw GradleException("KEY_PASSWORD环境变量未设置")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    compileOnly(libs.xposed.api)

    // Remote Preferences：配置由框架 daemon 直接推到 system_server
    implementation(libs.xposed.service)

    // 设置页用 Material 3（含 RecyclerView / AppCompat 传递依赖）
    implementation(libs.material)
    implementation(libs.androidx.appcompat)
}
