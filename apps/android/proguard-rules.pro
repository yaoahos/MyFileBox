# ============================================================
# MyFileBox R8 / ProGuard 规则（3.9.14 开启代码缩减）
# 目标：删除未用代码、混淆命名，同时保证传输/播放链路不因
#       反射、JNI、序列化被误裁。风险库整包保留。
# ============================================================

# ---- Release 去掉 Debug 日志 ----
# 只移除 v/d；i/w/e 保留，便于用户反馈传输/协议问题时定位。
# Log.d 的参数里有 runCatching 读取 PackageManager 的调试探测，纯读无副作用，
# 随日志一起被优化掉是安全的（不依赖它的返回值）。
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(java.lang.String, java.lang.String);
    public static int v(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int d(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String, java.lang.Throwable);
}

# ---- 崩溃堆栈可读性（混淆后保留行号，配合 mapping.txt 还原） ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions

# ---- 枚举：反射 values()/valueOf 依赖 ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- 本地方法 ----
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---- 序列化（若被库内部使用）----
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ============================================================
# 传输协议库：存在 Class.forName / 服务加载 / 内部反射
# 整包保留（体积小，换稳定）；R8 主要收益来自 Compose/AndroidX
# ============================================================

# SMB（smbj 0.13.0）：SPNEGO/NTLM、dialect、IPC 反射
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**

# SFTP（mwiede jsch 0.2.20）：Class.forName 选 cipher/zlib/compression
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# FTP（commons-net 3.11.1）
-keep class org.apache.commons.net.** { *; }
-dontwarn org.apache.commons.net.**

# 7z / 压缩（commons-compress 1.27.1 + xz 1.10）
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-keep class org.tukaani.xz.** { *; }
-dontwarn org.tukaani.xz.**

# 日志绑定（slf4j-nop）
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# mbassy（smbj 依赖的事件总线）可选 EL 过滤器引用 javax.el.*，
# 那是 Java EE 表达式引擎，Android 上不存在；EL 过滤是 mbassy
# 可选特性，SMB 链路不会走到，忽略缺失。
-dontwarn javax.el.**

# mbassy 事件总线：smbj 按类名字符串反射装载配置类（BusConfiguration
# 等），R8 一裁一改名 SMB 连接就 NoClassDefFoundError（3.9.14 真机实证：
# mapping 显示 R8$$REMOVED$$CLASS，dex 里整个 net/engio/mbassy 消失）。
# 整包保留（体积极小），名字也不能改。
-keep class net.engio.mbassy.** { *; }
-dontwarn net.engio.mbassy.**

# BouncyCastle：不由本 App 主动实例化 JCE Provider（源码无 addProvider / setProvider），
# smbj 的 NTLM 认证走 light API，其可达的 BC 类型由 R8 静态引用链自动保留。
# 此前整包 keep 把后量子（Picnic/lowmc）等数千个未用类一起打进 dex，实测：
# 去掉整包 keep 后 dex 10.06MB → 6.27MB、APK 4.74MB → 3.26MB；
# K20 Pro 真机已验证冷启动、SMB NTLM 列目录、群晖登录均正常（无 ClassNotFound）。
# 仅保留警告豁免，抑制 bcprov 中可选依赖（JCE/JDBC）的缺失告警。
-dontwarn org.bouncycastle.**

# ============================================================
# OkHttp / Okio：自带 consumer 规则，这里只补警告豁免
# ============================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ============================================================
# Kotlin / 协程常见可选类（离线构建下不引全量反射可选件）
# ============================================================
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
