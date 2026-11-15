import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 发布签名配置由本地构建环境提供，不纳入版本管理；缺少时回落 debug 签名，
// 保证任意机器都能编译出可调试的安装包。
val signingDir = rootProject.projectDir.resolve("signing")
val keystoreProps = Properties().apply {
    val f = signingDir.resolve("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val releaseKeystore = signingDir.resolve("myfilebox-release.jks")
// 本地回归允许 -PforceDebugSign=true 临时用 debug 签名打包：
// 签名一致才能覆盖安装，不必卸载、不会丢已保存的连接配置。正式发布不加该参数。
val forceDebugSign = (project.findProperty("forceDebugSign") as String?) == "true"
val hasReleaseKeystore = releaseKeystore.exists() && keystoreProps.isNotEmpty() && !forceDebugSign

android {
    namespace = "com.yaoahos.myfilebox.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yaoahos.myfilebox"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/{AL2.0,LGPL2.1}"
            )
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("releaseSign") {
                storeFile = releaseKeystore
                storePassword = keystoreProps.getProperty("releaseStorePassword")
                keyAlias = keystoreProps.getProperty("releaseKeyAlias")
                keyPassword = keystoreProps.getProperty("releaseKeyPassword")
            }
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
            // 有正式密钥就用正式签名；没有则回落 debug，保证任何机器都能编出可安装包。
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("releaseSign")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    // 7z 的 LZMA2 需要 xz-java
    implementation("org.tukaani:xz:1.10")
    implementation("org.slf4j:slf4j-nop:2.0.13")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}




