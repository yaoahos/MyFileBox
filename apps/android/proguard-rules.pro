# ============================================================
# MyFileBox R8 / ProGuard 规则（3.9.14 开启代码缩减）
# 目标：删除未用代码、混淆命名，同时保证传输/播放链路不因
#       反射、JNI、序列化被误裁。风险库整包保留。
# ============================================================

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

# BouncyCastle：JCE Provider 内部按「算法名 → 类名字符串」反射查找，
# 且 smbj 的 NTLM 摘要直接引用其 Digest 类型；改名会让 SMB 认证期崩溃。
# 整包保留换稳定（体量可接受），与传输库同一策略。
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ============================================================
# OkHttp / Okio：自带 consumer 规则，这里只补警告豁免
# ============================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ============================================================
# Kotlin / 协程常见可选类（离线构建下不引全量反射可选件）
# ============================================================
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
