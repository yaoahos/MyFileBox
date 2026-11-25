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
/**
 * 签名库路径：`keystore.properties` 里写的是 `signing/xxx.jks`（相对工程根），
 * 而 [signingDir] 本身已经是工程根的 `signing/`，直接 resolve 会变成
 * `signing/signing/xxx.jks`。只取文件名再拼，两种写法都能命中。
 */
fun keystoreIn(key: String, defaultName: String): File =
    signingDir.resolve(keystoreProps.getProperty(key, defaultName).trim().substringAfterLast('/'))

val releaseKeystore = keystoreIn("releaseStoreFile", "myfilebox-release.jks")
val debugKeystore = keystoreIn("debugStoreFile", "myfilebox-debug.jks")
// 本地回归允许 -PforceDebugSign=true 临时用 debug 签名打包（正式包名不变）：
// 签名一致才能覆盖安装，不必卸载、不会丢已保存的连接配置。正式发布不加该参数。
val forceDebugSign = (project.findProperty("forceDebugSign") as String?) == "true"
// 调试包：传 -PdebugBuild=true 时包名 com.yaoahos.myfilebox.debug、
// 应用名「MyFileBox 调试版」、用 signing/myfilebox-debug.jks 独立签名，
// 与正式版（包名 / 签名 / 数据目录 / FileProvider authority）全方位隔离，可同机共存。
// 不带参数就是正式版，包名与签名与已发布版本完全一致。
val debugBuild = (project.findProperty("debugBuild") as String?) == "true"
val hasReleaseKeystore = releaseKeystore.exists() && keystoreProps.isNotEmpty() && !forceDebugSign && !debugBuild

android {
    namespace = "com.yaoahos.myfilebox.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yaoahos.myfilebox" + if (debugBuild) ".debug" else ""
        minSdk = 26
        // 复核 2026-11：Google Play 自 2026-08-31 要求更新 target 36，
        // 但本项目通过 GitHub Release 分发，不受 Play 政策约束；本机 SDK 也只装了 35。
        // 升 36 会触发 Android 16 的 edge-to-edge / FGS 行为变更，留到发版前专项适配。
        targetSdk = 35
        versionCode = 7
        versionName = "1.1.5"
        // 调试包在「设置 → 关于」里也标出来，避免和正式版看混。
        if (debugBuild) versionNameSuffix = "-debug"
        // 应用显示名：默认走资源，调试包用独立名字，桌面上两个图标一眼可分。
        manifestPlaceholders["appLabel"] =
            if (debugBuild) "MyFileBox 调试版" else "@string/app_name"
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
        // 调试包专用签名：用 signing/myfilebox-debug.jks，与正式证书彻底不同。
        // agent 默认的 debug 配置用 ~/.android/debug.keystore，换机器就变，不能保证可覆盖；
        // 这里钉死项目自带的 jks，任何机器打出的调试包签名一致。
        if (debugKeystore.exists()) {
            create("debugSign") {
                storeFile = debugKeystore
                storePassword = keystoreProps.getProperty("debugStorePassword")
                keyAlias = keystoreProps.getProperty("debugKeyAlias")
                keyPassword = keystoreProps.getProperty("debugKeyPassword")
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
            // 调试包走独立 debugSign；正式包有正式密钥就用 releaseSign，
            // 没有则回落 AGP 默认 debug，保证任何机器都能编出可安装包。
            signingConfig = when {
                debugBuild && signingConfigs.findByName("debugSign") != null ->
                    signingConfigs.getByName("debugSign")
                hasReleaseKeystore -> signingConfigs.getByName("releaseSign")
                else -> signingConfigs.getByName("debug")
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




