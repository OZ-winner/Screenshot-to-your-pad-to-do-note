package com.oz.tabletshotbridge

import java.security.MessageDigest

fun main() {
    val bytes = byteArrayOf(1, 2, 3, 4)
    val valid = metadata(bytes)
    check(validateScreenshotMetadata(valid) == null)
    check(validateScreenshotPayload(valid, bytes) == null)

    val unsupported = valid.copy(mimeType = "image/webp")
    check(validateScreenshotMetadata(unsupported) == "不支持的截图格式")

    val wrongLength = valid.copy(byteLength = 99)
    check(validateScreenshotPayload(wrongLength, bytes) == "截图数据长度不匹配")

    val wrongHash = valid.copy(sha256 = "0".repeat(64))
    check(validateScreenshotPayload(wrongHash, bytes) == "截图数据校验失败")

    val binder = ScreenshotFrameBinder()
    val first = metadata(byteArrayOf(1), "first")
    val second = metadata(byteArrayOf(2), "second")
    check(binder.expect(first) == null)
    check(binder.expect(second) === first)
    check(binder.expire("first") == null)
    check(binder.take() === second)
    check(binder.take() == null)

    println("Android screenshot protocol checks passed")
}

private fun metadata(
    bytes: ByteArray,
    id: String = "capture-1",
): PendingScreenshotMetadata {
    val sha256 = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
    return PendingScreenshotMetadata(
        id = id,
        filename = "PC_20260804_120000.jpg",
        width = 1920,
        height = 1080,
        mimeType = "image/jpeg",
        byteLength = bytes.size.toLong(),
        sha256 = sha256,
    )
}
