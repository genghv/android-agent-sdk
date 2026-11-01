package ai.agentkernel.android.device

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.DeviceCapability
import ai.agentkernel.device.okJson
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 定位：`termux-location [-r once|last] [-p gps|network|passive] [-t 毫秒]`。
 *
 * ## 为什么用系统 `LocationManager`，不用 FusedLocationProvider
 *
 * `FusedLocationProviderClient` 来自 **GMS**（`com.google.android.gms:play-services-location`）：
 * - 国内设备大量没有 GMS（或没有可用的 Play 服务），引进来等于**多一个跑不起来的依赖**；
 * - 它是闭源二进制，给宿主 App 加一个「必须联网 + 必须装 Play 服务」的启动前提；
 * - GMS 定位在无 GMS 设备上的回退行为不受我们控制，**失败形态不可预期**。
 *
 * 系统 `LocationManager` 是 AOSP 的一部分，`gps` / `network` / `passive` 三个 provider
 * 在 API 24+ 上行为稳定；代价是没有 GMS 的「融合」优化（室内 network 定位慢一些），
 * 但失败可解释、可测、可控 —— 这比多一个厂商依赖划算。
 *
 * ## 不做后台定位（重要边界）
 *
 * 本能力**不申请** `ACCESS_BACKGROUND_LOCATION`，也**不常驻监听**：
 * 每次调用只做一次「请求 → 等 → 取一个值 → 立刻注销监听」，绝不写日志、绝不落盘。
 * 因此 App 在后台时被系统拒绝是**预期行为**，报错里会写明怎么处理（切前台）。
 *
 * ## 失败绝不编坐标
 *
 * 拿不到就是拿不到：抛 [CapabilityFailure]（注册表统一转 `{"ok":false,...}` + 退出码 1）。
 * 唯一允许的「兜底」是**最后已知位置**（`getLastKnownLocation`），且必须标 `stale:true`
 * —— 让模型知道这不是这一次请求拿到的实时坐标。
 *
 * ## 可测性：定位源抽成 [LocationSource]
 *
 * 真 `LocationManager` 只有设备上才有（还要权限、还要有星），JVM 单测跑不了。
 * 所以把「取数」抽成 [LocationSource]，能力本体只负责**参数、回落顺序、超时预算、JSON**；
 * 单测注入 [FakeLocationSource] 就能把「超时回落 / 无权限 / provider 不可用 / stale」全验掉。
 * 宿主若想换成自己的定位实现（例如自建融合），实现 [LocationSource] 注入即可。
 */
class LocationCapability(private val source: LocationSource) : DeviceCapability {

    /** 生产入口：走系统 [LocationManager]。 */
    constructor(context: Context) : this(AndroidLocationSource(context))

    override val name: String = "location"

    override val usage: String =
        "termux-location [-r once|last] [-p gps|network|passive] [-t 毫秒]"

    override fun run(args: List<String>): String {
        val opt = parseArgs(args)

        // 权限先查：没有就直接说清缺哪个、怎么授，**不发起任何请求**。
        if (source.permission() == LocationPermission.NONE) throw permissionHint()

        // 显式 `-p` ⇒ 只听它（用户点名要的，不偷偷换源）；
        // 不指定 ⇒ gps 优先，失败/超时回落 network。
        val chain = opt.provider?.let { listOf(it) } ?: listOf(PROVIDER_GPS, PROVIDER_NETWORK)

        return when (opt.mode) {
            RequestMode.LAST -> lastKnownOnly(chain)
            RequestMode.ONCE -> onceWithFallback(chain, opt.timeoutMs)
        }
    }

    // ---------------------------------------------------------------- 两种模式

    /** `-r last`：只读缓存，一定 `stale:true`（它本来就不是这次拿的）。 */
    private fun lastKnownOnly(chain: List<String>): String {
        for (provider in chain) {
            val fix = guarded { source.lastKnown(provider) } ?: continue
            return fixJson(fix, stale = true)
        }
        throw CapabilityFailure(
            "没有可用的最后已知位置（${chain.joinToString(" / ")} 都没有缓存值）。" +
                "先跑一次 `termux-location -r once` 建立缓存；" +
                "仍然没有就检查系统定位总开关是否打开、宿主是否已授予定位权限。",
        )
    }

    /**
     * `-r once`：按链依次请求；全部超时/失败 ⇒ 回落最后已知位置（`stale:true`）。
     *
     * ## `-t` 是**总预算**，不是每个 provider 各等一遍
     *
     * 用 `deadline` 卡总时长，并且把剩余预算**平分给还没试过的 provider**：
     * 双 provider、`-t 10000` ⇒ gps 等 5 秒，超时后 network 还剩 5 秒。
     *
     * 为什么要分：不分的话 gps 会把 10 秒吃干净，network 永远轮不到，
     * 「gps 超时回落 network」就成了空话（只能回落到缓存）；而 `-t 10000`
     * 在双 provider 下若各等一遍又变成最长 20 秒，与「超时 10 秒」的直觉不符。
     */
    private fun onceWithFallback(chain: List<String>, timeoutMs: Long): String {
        val notes = mutableListOf<String>()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L

        // 被动源不主动请求：它只在别的 App 定位时顺带收到，主动等只会白等。
        val liveProviders = chain.filter { it != PROVIDER_PASSIVE }
        if (liveProviders.size < chain.size) notes += "passive 是被动源，本能力不主动请求它"

        liveProviders.forEachIndexed { index, provider ->
            if (!guarded { source.isProviderEnabled(provider) }) {
                notes += "$provider 不可用（系统定位关闭，或设备没有这个 provider）"
                return@forEachIndexed
            }
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0L) {
                notes += "超时预算用完，没轮到 $provider"
                return@forEachIndexed
            }
            val slotsLeft = liveProviders.size - index
            val budgetMs = (remainingMs / slotsLeft).coerceAtLeast(1L)
            val fix = guarded { source.requestOnce(provider, budgetMs) }
            if (fix != null) return fixJson(fix, stale = false)
            notes += "$provider 在 ${budgetMs}ms 内没拿到定位"
        }

        // 回落：最后已知位置。**必须 stale:true** —— 不能让模型把旧坐标当实时坐标用。
        // 注意这里不按 isProviderEnabled 过滤：provider 关掉之前的缓存仍然有效。
        for (provider in chain) {
            val fix = guarded { source.lastKnown(provider) } ?: continue
            return fixJson(fix, stale = true)
        }

        throw CapabilityFailure(
            "取不到位置：${notes.joinToString("；")}；也没有可用的最后已知位置。" +
                "排查顺序：① 系统定位总开关；② 宿主是否授了定位权限；" +
                "③ 室内 GPS 可能长时间无星，可试 `-p network`；④ 首次使用还没有缓存时 `-r last` 必然为空。",
        )
    }

    // ---------------------------------------------------------------- 参数

    private data class Options(
        val mode: RequestMode,
        val provider: String?,
        val timeoutMs: Long,
    )

    private enum class RequestMode { ONCE, LAST }

    private fun parseArgs(args: List<String>): Options {
        var mode = RequestMode.ONCE
        var provider: String? = null
        var timeoutMs = DEFAULT_TIMEOUT_MS

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when (arg) {
                "-r" -> {
                    val value = args.getOrNull(i + 1)
                    mode = when (value) {
                        "once" -> RequestMode.ONCE
                        "last" -> RequestMode.LAST
                        "updates" -> throw CapabilityFailure(
                            "不支持 `-r updates`（连续定位要常驻监听，本能力只取一次）。用法：$usage",
                        )
                        else -> throw usageError("未知的 -r 值：${value ?: "（缺参数）"}")
                    }
                    i += 2
                }
                "-p" -> {
                    val value = args.getOrNull(i + 1)
                    if (value == null || value !in PROVIDERS) {
                        throw usageError(
                            "未知的 -p 值：${value ?: "（缺参数）"}（只支持 ${PROVIDERS.joinToString(" / ")}）",
                        )
                    }
                    provider = value
                    i += 2
                }
                "-t" -> {
                    val raw = args.getOrNull(i + 1)
                    val value = raw?.toLongOrNull()
                        ?: throw usageError("非法的 -t 值：${raw ?: "（缺参数）"}（要毫秒数，例如 -t 5000）")
                    if (value <= 0L) throw usageError("-t 必须大于 0（收到 $value）")
                    if (value > MAX_TIMEOUT_MS) {
                        throw usageError(
                            "-t 最长 $MAX_TIMEOUT_MS 毫秒（shell 工具默认 60 秒就掐断，再长没有意义）",
                        )
                    }
                    timeoutMs = value
                    i += 2
                }
                // Termux:API 的 `-i`（连续定位间隔）在这里没有对应语义，明确拒绝而不是默默忽略。
                "-i" -> throw CapabilityFailure(
                    "不支持 `-i`（不做连续/后台定位）：本能力只取一次坐标。用法：$usage",
                )
                else -> throw usageError("看不懂的参数：$arg")
            }
        }
        return Options(mode, provider, timeoutMs)
    }

    private fun usageError(detail: String): CapabilityFailure = CapabilityFailure(
        "$detail。用法：$usage\n" +
            "  -r once   取一次新坐标（默认）；超时回落最后已知位置并标 stale:true\n" +
            "  -r last   只读最后已知位置（一定 stale:true）\n" +
            "  -p gps|network|passive  指定定位源；不指定时 gps 优先、失败/超时回落 network\n" +
            "  -t 毫秒   总超时预算（默认 $DEFAULT_TIMEOUT_MS，最长 $MAX_TIMEOUT_MS）\n" +
            "  没有权限会明确报错，绝不返回假坐标。",
    )

    private fun permissionHint(): CapabilityFailure = CapabilityFailure(
        "缺定位权限：ACCESS_FINE_LOCATION 与 ACCESS_COARSE_LOCATION 都没授。" +
            "修法：① 在 AndroidManifest 里声明 " +
            "<uses-permission android:name=\"android.permission.ACCESS_FINE_LOCATION\" />" +
            "（本库的清单没有声明它，得由宿主声明）；" +
            "② 运行时用 requestPermissions(...) 申请；" +
            "③ 用户只给「大致位置」时只有 ACCESS_COARSE_LOCATION，精度会降到公里级。",
    )

    // ---------------------------------------------------------------- 输出

    /**
     * 成功 JSON。
     *
     * 字段固定六个（`latitude` / `longitude` / `accuracy` / `provider` / `timestamp` / `stale`），
     * 不多加：这是给模型看的**契约**，加字段就等于改契约。
     * - `accuracy` 单位米；`timestamp` 是**定位时刻**的毫秒时间戳（不是调用时刻）；
     * - `provider` 取**这个坐标实际的来源**（回落时可能是 network，而不是请求的 gps）。
     */
    private fun fixJson(fix: LocationFix, stale: Boolean): String = okJson {
        put("latitude", fix.latitude)
        put("longitude", fix.longitude)
        put("accuracy", fix.accuracyMeters)
        put("provider", fix.provider)
        put("timestamp", fix.timestampMs)
        put("stale", stale)
    }

    /** 系统级拒绝（权限被撤 / 后台被拒）→ 一句能照做的中文，而不是把 SecurityException 甩给模型。 */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw CapabilityFailure(
            "系统拒绝定位（SecurityException：${e.message ?: "无详情"}）：权限可能被撤销，" +
                "或 App 当前在后台 —— Android 10+ 的后台定位要 ACCESS_BACKGROUND_LOCATION，" +
                "本能力不申请它、也不做后台定位。把 App 切到前台后重试。",
        )
    }

    companion object {
        const val PROVIDER_GPS = "gps"
        const val PROVIDER_NETWORK = "network"
        const val PROVIDER_PASSIVE = "passive"

        val PROVIDERS = setOf(PROVIDER_GPS, PROVIDER_NETWORK, PROVIDER_PASSIVE)

        /** 默认总超时。与 Termux:API 的 `termux-location` 默认值一致（10 秒）。 */
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /** 上限：shell 工具默认 60 秒就掐断，超过它没有意义。 */
        const val MAX_TIMEOUT_MS = 60_000L
    }
}

/** 一次定位结果（与 Android `Location` 解耦，JVM 单测才能造）。 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    /** 水平精度，单位米。 */
    val accuracyMeters: Float,
    /** 这个坐标的实际来源：gps / network / passive。 */
    val provider: String,
    /** 定位时刻（毫秒时间戳），不是调用时刻。 */
    val timestampMs: Long,
)

/** 当前定位权限档位。只有 COARSE 时系统会把精度模糊到公里级。 */
enum class LocationPermission {
    /** 精确位置（ACCESS_FINE_LOCATION）。 */
    FINE,

    /** 仅大致位置（ACCESS_COARSE_LOCATION）。 */
    COARSE,

    /** 都没有。 */
    NONE,
}

/**
 * 定位源 —— 能力本体与 Android API 之间的唯一接缝。
 *
 * 抽出来是为了两件事：
 * 1. **JVM 可测**：真 `LocationManager` 只有设备上有，单测注入假实现就能验回落与失败形态；
 * 2. **宿主可换**：想接自建融合定位的宿主，实现本接口再注入 [LocationCapability] 即可。
 *
 * 约定：实现**不要**自己吞掉 `SecurityException` —— 让能力统一翻译成「缺权限/后台被拒」，
 * 否则模型分不清「没星」和「没权限」。
 */
interface LocationSource {
    fun permission(): LocationPermission

    /** provider 是否可用（系统定位总开关关了就全 false；不抛异常）。 */
    fun isProviderEnabled(provider: String): Boolean

    /** 最后已知位置；没有就 null。可能抛 [SecurityException]。 */
    fun lastKnown(provider: String): LocationFix?

    /**
     * 请求一次定位，最多等 [timeoutMs] 毫秒。
     *
     * @return 拿到的坐标；超时或没有结果返回 null（**不要**拿缓存冒充）。
     */
    fun requestOnce(provider: String, timeoutMs: Long): LocationFix?
}

/**
 * 生产实现：系统 [LocationManager]（AOSP，**不依赖 GMS**）。
 *
 * 监听器一拿到第一个坐标就注销 —— 不做常驻监听，不留后台线程。
 */
class AndroidLocationSource(private val context: Context) : LocationSource {

    /** 懒取：某些裁剪 ROM 没有 LOCATION_SERVICE，构造函数不该因此炸。 */
    private val manager: LocationManager? by lazy {
        runCatching { context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager }.getOrNull()
    }

    override fun permission(): LocationPermission = when {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationPermission.FINE
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationPermission.COARSE
        else -> LocationPermission.NONE
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun isProviderEnabled(provider: String): Boolean {
        val manager = manager ?: return false
        return runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
    }

    override fun lastKnown(provider: String): LocationFix? {
        val manager = manager ?: return null
        return manager.getLastKnownLocation(provider)?.toFix()
    }

    override fun requestOnce(provider: String, timeoutMs: Long): LocationFix? {
        val manager = manager ?: return null
        // 回调线程：主 Looper 一定存在；没有主 Looper 的进程（理论上不存在）直接放弃请求。
        val looper = Looper.getMainLooper() ?: Looper.myLooper() ?: return null

        val latch = CountDownLatch(1)
        // 监听器在主线程回调、等待方在 IO 线程 → 用 AtomicReference 保证可见性（普通 var 会被优化掉）。
        val received = AtomicReference<Location?>(null)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // 只要第一个；后来的回调不再覆盖（compareAndSet 保证幂等）。
                if (received.compareAndSet(null, location)) latch.countDown()
            }

            // 下面三个在 API 30+ 有默认实现，但 minSdk 24 的旧接口里是抽象方法：
            // 显式实现一遍，避免在 Android 7/8 上因缺方法而 AbstractMethodError。
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        try {
            // 0ms / 0m：只要一有坐标就回调，不做节流。
            manager.requestLocationUpdates(provider, 0L, 0f, listener, looper)
        } catch (e: SecurityException) {
            throw e
        } catch (e: RuntimeException) {
            // provider 不存在 / 参数被系统嫌弃 → 当作「拿不到」，由能力回落。
            return null
        }

        return try {
            latch.await(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            received.get()?.toFix()
        } finally {
            // 无论拿到没拿到都要注销监听：不注销会一直占着定位会话（耗电 + 后台限制）。
            runCatching { manager.removeUpdates(listener) }
        }
    }

    private fun Location.toFix(): LocationFix = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        provider = provider ?: "unknown",
        timestampMs = time,
    )
}
