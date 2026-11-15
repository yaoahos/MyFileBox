package com.yaoahos.myfilebox.android.data

/**
 * 把 HTTP 状态码翻成一句用户能照着做的话。
 *
 * WebDAV / HTTP 类服务的失败原因几乎都能从状态码直接读出结论：401 是账号、
 * 403 是权限、404 是路径、507 是空间。而「HTTP 404」这种输出等于把一张状态码表
 * 塞给用户，还要他自己查——等于没提示。
 *
 * [action] 是动作前缀（「WebDAV 上传失败」等），保持各协议自己的口径。
 * 文案只留结论与一个动作，状态码数字不铺在提示里：提示条一行放不下，
 * 真有需要看日志。
 */
fun httpStatusHint(action: String, code: Int): String = when (code) {
    400 -> "$action：请求被拒绝，请检查路径"
    401 -> "$action：认证失败，请检查账号密码"
    403 -> "$action：没有访问权限"
    404 -> "$action：路径不存在"
    405 -> "$action：服务器不支持此操作，可能不是 WebDAV 地址"
    409 -> "$action：父目录不存在"
    423 -> "$action：目标被占用，请稍后重试"
    507 -> "$action：服务端空间不足"
    // 5xx 只有一个结论：对端出错，不是用户填错了什么。
    500, 502, 503, 504 -> "$action：服务端错误，多为存储离线或超时"
    else -> "$action：HTTP $code"
}
