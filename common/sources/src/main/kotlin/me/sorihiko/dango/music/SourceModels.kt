package me.sorihiko.dango.music

import java.net.CookieManager
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class MusicSource(val key: String, val label: String) {
    NETEASE("netease", "NetEase"), QQ("qq", "QQ Music"), KUGOU("kugou", "Kugou");
}

class SourceAccessException(message: String) : IllegalStateException(message)
class SourceAudio(val url: String, val trialOnly: Boolean)

/** Credentials are supplied by the caller, never loaded from global client state. */
class NativeAccount @JvmOverloads constructor(
    val source: MusicSource,
    val userId: String,
    val nickname: String,
    val token: String,
    val deviceId: String,
    val cookies: CookieManager,
    val loginType: Int = 2,
    val avatarUrl: String = "",
)

object SourceIdentity {
    @JvmStatic fun stableId(source: MusicSource, nativeId: String): Long = ByteBuffer.wrap(
        MessageDigest.getInstance("SHA-256").digest("${source.key}:$nativeId".toByteArray(Charsets.UTF_8))
    ).long or Long.MIN_VALUE
}

object CookieValues {
    @JvmStatic fun cookie(manager: CookieManager, name: String): String =
        manager.cookieStore.cookies.firstOrNull { it.name == name && !it.hasExpired() }?.value.orEmpty()
    @JvmStatic @JvmOverloads fun hash33(value: String, seed: Int = 5381): Int {
        var hash = seed
        value.forEach { hash = hash * 33 + it.code }
        return hash and Int.MAX_VALUE
    }
}
