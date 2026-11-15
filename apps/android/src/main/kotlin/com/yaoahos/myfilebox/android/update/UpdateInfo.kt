package com.yaoahos.myfilebox.android.update

/** 一条更新日志 / 发布说明。 */
data class ReleaseNote(
    val version: String,
    val name: String,
    val notes: String,
    val date: String
)

/** 一次「有新版本」的完整信息，用于弹窗与下载。 */
data class UpdateInfo(
    val version: String,
    val tag: String,
    val name: String,
    val notes: String,
    val apkUrl: String,
    val pageUrl: String,
    val size: Long
)

/**
 * 检查更新的失败原因。每种原因都有自己的提示语，
 * 不要用一句「检查更新失败」把限流、404、服务端故障混在一起。
 */
sealed class UpdateException(message: String) : Exception(message)

/** GitHub 限流（未登录每 IP 每小时 60 次）。 */
class RateLimitedException(val resetEpoch: Long? = null) : UpdateException("更新检查已被限制，请稍后再试")

/** 仓库还没有发布过正式版本（HTTP 404）。 */
class NoReleaseException : UpdateException("还没有可更新的版本")

/** 更新服务返回 4xx/5xx（除限流与 404 外）。 */
class UpdateUnavailableException(val status: Int) : UpdateException("更新服务暂时不可用：HTTP $status")

/** 返回内容不是预期 JSON，多半被代理或认证页改写。 */
class BadPayloadException : UpdateException("更新服务返回内容异常")

/** 下载安装包失败。消息可直接展示给用户。 */
class UpdateDownloadException(message: String) : UpdateException(message)
