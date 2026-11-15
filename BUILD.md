# MyFileBox Android 构建说明

Kotlin + Jetpack Compose 客户端，模块化 `core` / `apps:android`。软件介绍、功能一览、安全设计与版本记录见 [README.md](README.md)。

## 环境

- JDK 17
- Gradle 8.11.1
- compileSdk / targetSdk 35
- minSdk 26

## 构建命令

```powershell
$env:JAVA_HOME = '<本机 JDK 17 安装目录>'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
gradle :apps:android:assembleRelease --no-daemon
```

产物：`apps/android/build/outputs/apk/release/android-release.apk`

## 校验

```
gradle :apps:android:test                 # 单元测试
gradle :apps:android:lintDebug            # lint
apksigner verify --verbose <apk>
aapt dump badging <apk>
```

- package：`com.yaoahos.myfilebox`
- 权限：`INTERNET`、`ACCESS_NETWORK_STATE`、存储读写 / 媒体读取、`MANAGE_EXTERNAL_STORAGE`、`REQUEST_INSTALL_PACKAGES`、`POST_NOTIFICATIONS`、前台传输服务

## 权限说明

- 本地文件管理需要「所有文件访问权限」；应用内点「去授权」会先说明，再打开系统授权页。
- 「安装未知应用」为系统级权限，需在系统设置中为本应用单独允许。
- 真实 NAS / WebDAV / SMB / FTP / SFTP 端到端功能需要用户自己的服务器与账号。
