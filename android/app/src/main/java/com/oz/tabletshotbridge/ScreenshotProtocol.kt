package com.oz.tabletshotbridge

import java.security.MessageDigest

internal val SUPPORTED_SCREENSHOT_MIME_TYPES = setOf("image/jpeg", "image/png")

internal data class PendingScreenshotMetadata(
    val id: String,
    val filename: String,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val byteLength: Long,
    val sha256: String,
)

internal class ScreenshotFrameBinder {
    private var pending: PendingScreenshotMetadata? = null

    @Synchronized
    fun expect(metadata: PendingScreenshotMetadata): PendingScreenshotMetadata? {
        return pending.also { pending = metadata }
    }

    @Synchronized
    fun take(): PendingScreenshotMetadata? {
        return pending.also { pending = null }
    }

    @Synchronized
    fun expire(id: String): PendingScreenshotMetadata? {
        val current = pending ?: return null
        if (current.id != id) return null
        pending = null
        return current
    }

    @Synchronized
    fun clear() {
        pending = null
    }
}

internal fun validateScreenshotMetadata(metadata: PendingScreenshotMetadata): String? {
    if (metadata.mimeType !in SUPPORTED_SCREENSHOT_MIME_TYPES) return "不支持的截图格式"
    if (metadata.byteLength <= 0 || metadata.width <= 0 || metadata.height <= 0) {
        return "截图元数据无效"
    }
    if (!metadata.sha256.matches(Regex("[0-9a-f]{64}"))) return "截图校验信息无效"
    return null
}

internal fun validateScreenshotPayload(
    metadata: PendingScreenshotMetadata,
    bytes: ByteArray,
): String? {
    if (bytes.size.toLong() != metadata.byteLength) return "截图数据长度不匹配"
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val actualSha256 = digest.joinToString("") { "%02x".format(it) }
    if (actualSha256 != metadata.sha256) return "截图数据校验失败"
    return null
}
