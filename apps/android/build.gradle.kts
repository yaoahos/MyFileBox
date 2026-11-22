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
// 对比测试包：传 -PcompareSuffix=.compare 时包名换成 com.yaoahos.myfilebox.compare，
// 可与正式版/旧版同机共存做 A/B 对比；不传则与原来完全一致。
// 包名变了 FileProvider authority（${applicationId}.files）和数据目录会一起隔离，
// 两个 App 的配置、记录、回收站互不影响。
val compareSuffix = (project.findProperty("compareSuffix") as String?)?.trim()?.takeIf { it.isNotEmpty() }
val hasReleaseKeystore = releaseKeystore.exists() && keystoreProps.isNotEmpty() && !forceDebugSign

android {
    namespace = "com.yaoahos.myfilebox.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yaoahos.myfilebox" + (compareSuffix ?: "")
        minSdk = 26
        // 复核 2026-11：Google Play 自 2026-08-31 要求更新 target 36，
        // 但本项目通过 GitHub Release 分发，不受 Play 政策约束；本机 SDK 也只装了 35。
        // 升 36 会触发 Android 16 的 edge-to-edge / FGS 行为变更，留到发版前专项适配。
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.2"
        // 对比包在「设置 → 关于」里也标出来，避免和正式版看混。
        if (compareSuffix != null) versionNameSuffix = "-compare"
        // 应用显示名：默认走资源，对比包加后缀，桌面上两个图标才分得清。
        manifestPlaceholders["appLabel"] =
            if (compareSuffix != null) "MyFileBox 对比版" else "@string/app_name"
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
                "META-INF/{AL2.0,LGPL2.1}",
                // 依赖升级后两份库都带 OSGi 模块元数据（Android 不做 OSGi 解析），
                // 同名 MANIFEST.MF 会在 mergeReleaseJavaResource 报 duplicate。
                "META-INF/versions/**/OSGI-INF/MANIFEST.MF",
                // BouncyCastle 后量子（Picnic/lowmc）参数：本项目仅用 BC 的摘要/ASN.1/Cipher 做 SMB 认证，
                // 不调用任何 PQC 算法；这些参数约 1.22MB 且压缩率近 0，属纯冗余资源。
                "org/bouncycastle/pqc/**",
                // commons-codec 的 Soundex/Beider-Morse 语音匹配规则，由 commons-compress 传递带入，
                // 源码零引用，仅做文件压缩/解压无需语音相似度算法。
                "org/apache/commons/codec/language/bm/**"
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
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    // 自动同步：周期任务交给 WorkManager，省电策略下由系统统一调度，不必自建常驻服务。
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    // 带密码的 zip 读写：JDK 和 commons-compress 都不支持加密，zip4j 支持 AES / ZipCrypto。
    implementation("net.lingala.zip4j:zip4j:2.11.6")
    // 7z 的 LZMA2 需要 xz-java
    implementation("org.tukaani:xz:1.10")
    implementation("org.slf4j:slf4j-nop:2.0.13")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // 单元测试跑在 JVM 上，android.jar 里的 org.json 只是桩（方法全返回默认值），
    // 备份载荷的结构测试需要一份真实实现。
    testImplementation("org.json:json:20240303")
}




