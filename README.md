# MyFileBox

MyFileBox 是一个面向群晖 NAS 用户的文件管理客户端。当前提供 Android 客户端，使用 Kotlin + Jetpack Compose 实现；共享核心为纯 Kotlin/JVM。本地文件、群晖 DSM、WebDAV / SMB / FTP / SFTP 网络位置在同一套界面里统一管理，支持跨端下载与上传、同端复制与移动。

- 项目名：`MyFileBox`
- 包名：`com.yaoahos.myfilebox`
- 当前版本：`1.0.0`（versionCode 1）
- 最低系统：Android 8.0（API 26）
- 编译 / 目标 SDK：Android 15（API 35）
- 发布资产：`MyFileBox-1.0.0-release.apk` · 5,968,633 字节
- SHA-256：`4326a24000b8c3cf1b769fd3fe738c2c2b18c7b7bc7875694432f5ae8844ccd7`
- 发布下载：[Releases](https://github.com/yaoahos/MyFileBox/releases)

> MyFileBox 为独立开发的第三方客户端，与相关品牌厂商均无关联。

* * *

## 一、软件介绍

MyFileBox 面向同时拥有手机本地文件与 NAS / 网络存储的用户。它不注册账号、不接入统计与广告 SDK，所有连接凭据加密保存在本机，文件在设备与存储服务器之间直接传输。

设计目标：

1. **统一入口**：本地、群晖、网络三类存储同一套浏览、传输与管理体验。
2. **直连传输**：文件不经任何第三方服务器中转。
3. **凭据加密**：账号、密码、会话凭据使用 Android Keystore 派生密钥加密存储。
4. **克制**：不申请无关权限，不做后台常驻以外的额外行为。
5. **跨尺寸可用**：手机、平板和横屏共用一套 Compose UI。

* * *

## 二、功能一览

**本地文件**

- 分类入口（图片 / 视频 / 音频 / 文档 / 安装包 / 压缩包 / 最近 / 收藏），宫格与列表视图，按名称 / 日期 / 大小排序。
- 复制、移动、删除、重命名、分享、多选批量操作；ZIP 内建解压（含 zip-slip 路径校验）。
- 回收站：删除先进回收站，支持恢复、清空与按 7/30/90 天自动清理。
- 默认下载位置可配置，支持在选择器内新建文件夹。

**群晖 NAS**

- 登录支持 IP / 域名 / QuickConnect ID 自动识别；局域网、直连与中继通道自动选择。
- 共享文件夹浏览、多存储卷、文件上传 / 下载、复制 / 移动、官方收藏。
- 回收站（#recycle）浏览、还原、删除，删除单元语义完整（文件夹整删整还原）。
- 网络切换后自动重解析通道并重连。

**网络位置**

- WebDAV / SMB / FTP / SFTP 四种协议；连接新增、编辑、删除、测试连接。
- 目录浏览、上传、下载、删除远端文件；连接信息可加密保存。
- 音视频走流式在线播放（本机代理转发，支持拖动进度），不必下载整个文件。

**传输中心**

- 上传 / 下载任务实时进度、暂停、恢复、取消、删除记录。
- 删除任务可同步清理本地半成品（.part）与远端已上传文件。
- 任务文件丢失状态检测，支持重新下载。

**打开方式**

- 所有文件类型统一走应用内「打开方式」：点应用仅本次打开，点 ☆ 始终用它打开，可在设置里改回询问。
- 远程大文件直接提示先下载，小文件缓存后交给所选应用；音视频优先流式播放。

**外观**

- 外观模式：跟随系统 / 日间 / 夜间。
- 主题配色：20 个预置颜色，也可输入 `#RRGGBB` 自选。
- 动态取色：Android 12 及以上可从桌面壁纸生成整套配色（Material You）。
- 以上三项集中在「设置 → 主题与外观」。
- 底栏顺序、显示开关、默认启动页可配置。

* * *

## 三、技术选型

| 层次 | 选型 | 说明 |
|------|------|------|
| 语言 | Kotlin 2.1.0 | Android 与共享核心均使用 Kotlin |
| UI | Jetpack Compose + Material 3 | 声明式 UI，支持手机 / 平板 |
| 构建 | Gradle 8.11.1 + AGP 8.7.3 | JDK 17 字节码 |
| 共享核心 | 纯 Kotlin/JVM `:core` | 模型、本地仓库规则、工具函数 |
| 群晖 | DSM WebAPI（entry.cgi） | 登录、文件、回收站、收藏、缩略图 |
| 网络 | OkHttp（WebDAV）+ smbj（SMB）+ commons-net（FTP）+ JSch（SFTP） | 四协议统一抽象 |
| 流式代理 | 本机 loopback HTTP 代理 | 为外部播放器提供可拖动进度的 HTTP 流 |
| 凭据加密 | Android Keystore AES-GCM | 连接凭据与会话落盘加密 |
| 设置存储 | SharedPreferences | 普通偏好与加密凭据分离 |

* * *

## 四、安全设计

**凭据与会话**

- 连接密码、群晖账号与会话凭据使用 Android Keystore 的 AES-256-GCM 密钥加密后落盘；加密失败时不回退明文写入。
- 明文仅存在于内存中的当前连接状态，进程结束即消失。

**网络**

- 文件传输直连目标服务器，不经任何第三方中转；不接入统计、广告、崩溃上报 SDK。
- 明文流量（FTP / SMB / HTTP WebDAV / HTTP DSM）出于协议本身需要而允许，连接表单内有明确提示，HTTPS / FTPS / SFTP 为推荐选项；TLS 仅信任系统 CA。
- 在线播放代理仅监听 `127.0.0.1`，不对外网开放；播放地址含随机 key，进程结束即失效。

**组件与权限**

- `android:allowBackup="false"`，避免凭据经系统备份外泄。
- Service / Receiver 均不导出；对外仅保留启动 Activity。
- 权限仅文件读写、网络、前台传输服务与安装更新所需各项。

* * *

## 五、项目构建

**环境要求**：JDK 17；Android SDK Platform 35；Gradle 8.11.1。

**调试构建**：

```
gradle :apps:android:assembleDebug
```

**正式构建**：

```
gradle :apps:android:assembleRelease
```

环境要求、校验命令与权限说明见 [BUILD.md](BUILD.md)。

产物：`apps/android/build/outputs/apk/release/android-release.apk`

**项目结构**（Gradle 多模块，`:core` 为纯 Kotlin/JVM 库，`:apps:android` 为 Android 应用）：

```
MyFileBox/                                  仓库根目录
├── settings.gradle.kts                     模块声明：:core、:apps:android
├── build.gradle.kts / gradle.properties    根工程插件、依赖仓库与构建参数
├── apps/android/                           Android 应用模块
│   ├── build.gradle.kts                    版本号、依赖与 R8 配置
│   ├── proguard-rules.pro                  混淆保留规则（传输 / 协议库）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml         权限、组件与 9 档桌面图标入口
│       │   ├── assets/updateLog.md         应用内「更新日志」弹窗的数据源
│       │   ├── kotlin/com/yaoahos/myfilebox/android/
│       │   │   ├── MainActivity.kt 等      应用入口、Application、前台传输服务与通知
│       │   │   ├── data/                   各协议数据源、凭据加密、流播代理、网络状态
│       │   │   ├── ui/                     Compose 页面与组件、AppViewModel、设置页
│       │   │   │   └── theme/              配色方案、动态取色、壁纸取色
│       │   │   └── update/                 GitHub 更新检查、版本比较、日志解析
│       │   └── res/                        adaptive 图标、9 档图标色值、主题、FileProvider
│       └── test/                           JVM 单元测试（数据源规则、图标分档、日志解析）
├── core/                                   纯 Kotlin/JVM 共享核心
│   └── src/main/kotlin/com/yaoahos/myfilebox/core/
│       ├── Models.kt                       数据模型与偏好定义
│       └── IconTint.kt                     图标柔色算法与色相分档
├── BUILD.md                                环境、构建命令与权限说明
├── CHANGELOG.md                            完整版本日志
├── LICENSE
└── README.md
```

模块职责：

- `core`：不依赖 Android API，可在 JVM 上直接跑单测；`apps:android` 依赖它。
- `data/`：本地文件、群晖 DSM、WebDAV / SMB / FTP / SFTP 六类仓库，以及 `SecretVault`（凭据加密）、`StreamProxy`（在线播放）、`NetStatus` / `ChannelPolicy`（网络状态与通道判择）、传输进度流。
- `ui/`：`AppRoot` / `MainShell` 负责导航与外壳，`AppViewModel` 持有页面状态，`BrowserScreen`、设置页、传输中心等按页面拆分。
- `update/`：读取 GitHub Releases、按数字段比较版本、把 Markdown 日志清洗成应用内弹窗条目。

* * *

## 六、兼容性与限制

- 仅支持 Android 8.0 及以上。
- 群晖上传 / 新建依赖目标 DSM 的 FileStation 接口可用性。
- 在线播放依赖外部播放器对 HTTP 流的支持；个别播放器不认 http 源时自动退回「缓存后打开」。
- SFTP 大文件拖动进度依赖跳读性能，超大文件建议先下载。
- 内置 FTP 服务器、远程回收站、批量重命名、7z / tar 压缩包管理未实现。

* * *

## 七、版本记录

**2026/11/15 · 1.0.0**
- 本地文件、群晖 NAS 与 WebDAV / SMB / FTP / SFTP 网络位置统一管理
- 目录浏览、搜索、名称筛选、排序与多选批量操作，分类入口快速找文件
- 跨端上传与下载、同端复制与移动，任务支持暂停、恢复与取消
- 音视频在线流式播放，拖动进度不必下载整个文件
- 图片与文档在线预览，ZIP 内建解压，回收站与收藏管理
- 群晖支持 IP / 域名 / QuickConnect 登录、官方收藏与回收站
- 打开方式可指定默认应用，未知类型文件按后缀识别
- 深浅色外观、预置配色、自定义颜色与动态取色集中管理
- 应用图标可随主题色变化，颜色柔和处理，桌面图标按色相换色
- 下拉刷新只刷新当前页面，切换网络后自动重建通道并读回目录
- 连接失败区分没网、飞行模式、地址端口错、认证失败与服务端状态
- 时间格式统一左对齐，分隔线整行贯通，分类排序改 4×2 网格
- 连接凭据加密保存在本机，文件直连传输不经任何第三方服务器

本处只列最近 5 个版本。更早的版本与完整日志见 [Releases](https://github.com/yaoahos/MyFileBox/releases)。
