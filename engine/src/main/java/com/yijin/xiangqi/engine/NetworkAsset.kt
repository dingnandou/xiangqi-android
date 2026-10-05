package com.yijin.xiangqi.engine

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * NNUE 网络文件的准备与校验。
 *
 * 为什么需要这一步：
 *  - assets 在 APK 里不是普通文件路径（可能被压缩、可能按 split 存放），
 *    而 Pikafish 的网络加载器走的是 `std::ifstream`，必须拿到真实文件路径。
 *  - Pikafish 在网络与引擎版本不配套时会直接 `exit(EXIT_FAILURE)`
 *    （nnue/network.cpp:160），也就是**杀掉整个 App 进程**。
 *    所以必须在进入 JNI 之前，用大小 + SHA256 把不配套的网络挡在外面。
 *
 * 首次校验通过后写入 stamp 文件，之后启动只比对文件大小，避免每次启动重算
 * 48 MB 的哈希。
 */
object NetworkAsset {

    /** 引擎默认网络文件名。必须是这个名字，否则 verify_network() 会判定失败。 */
    const val FILE_NAME = "pikafish.nnue"

    private const val STAMP_NAME = "$FILE_NAME.verified"

    /** 锁定的 Pikafish 2026-09-06 官方 release 自带网络。 */
    const val EXPECTED_SHA256 = "7D13D73569A9B571BA0EB20CF1596247BC2A42738967E61AFEF6482B231E900E"
    const val EXPECTED_BYTES = 50_706_378L

    sealed interface Result {
        data class Ready(val directory: File, val verifiedNow: Boolean) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * 把网络文件准备到 `context.filesDir/network`，并确保它可用。
     *
     * @param verify true 表示强制重算 SHA256（M0 验证页面用）。
     */
    fun prepare(context: Context, verify: Boolean = false): Result {
        val targetDir = File(context.filesDir, "network")
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return Result.Failed("无法创建目录：${targetDir.absolutePath}")
        }
        val target = File(targetDir, FILE_NAME)
        val stamp = File(targetDir, STAMP_NAME)

        val alreadyVerified =
            target.exists() &&
                target.length() == EXPECTED_BYTES &&
                stamp.exists() &&
                stamp.readText() == EXPECTED_SHA256

        if (alreadyVerified && !verify) {
            return Result.Ready(targetDir, verifiedNow = false)
        }

        return try {
            if (!target.exists() || target.length() != EXPECTED_BYTES) {
                copyAsset(context, target)
            }

            val digest = sha256(target)
            if (digest != EXPECTED_SHA256) {
                // 文件放错或损坏：不删除，留给用户排查，只如实报告。
                Result.Failed(
                    "网络文件校验不通过。\n期望 $EXPECTED_SHA256\n实际 $digest\n" +
                        "该网络与锁定的 Pikafish 版本不配套，加载会导致进程退出。"
                )
            } else {
                stamp.writeText(EXPECTED_SHA256)
                Result.Ready(targetDir, verifiedNow = true)
            }
        } catch (e: IOException) {
            Result.Failed("网络文件准备失败：${e.message ?: e::class.java.simpleName}")
        }
    }

    private fun copyAsset(context: Context, target: File) {
        context.assets.open(FILE_NAME).use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }
}