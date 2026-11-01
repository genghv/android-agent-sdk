package ai.agentkernel.android.device

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.DeviceCapabilityRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `termux-location` 的 JVM 单测 —— **不碰设备**。
 *
 * 真的 `LocationManager` 要权限、要有星、要有真机，单测跑不了；
 * 所以能力本体只依赖 [LocationSource]，这里注入 [FakeLocationSource]，
 * 把「回落顺序 / 超时 / 无权限 / provider 不可用 / stale 标记 / 字段契约」逐条钉死。
 *
 * 真机那一层（系统真的给了坐标、StrongBox 无关、监听注销干净）属于仪器化测试的范围。
 */
class LocationCapabilityTest {

    /** 可编排的假定位源：想要什么结果就配什么。 */
    private class FakeLocationSource(
        var permission: LocationPermission = LocationPermission.FINE,
        var enabled: Set<String> = setOf("gps", "network"),
        var cached: Map<String, LocationFix> = emptyMap(),
        var live: Map<String, LocationFix> = emptyMap(),
        /** true ⇒ 所有取数调用都抛 SecurityException（模拟后台被拒/权限被撤）。 */
        var deny: Boolean = false,
    ) : LocationSource {

        /** 记录「真的发起了哪些实时请求、每次给了多少毫秒」。 */
        val liveRequests = mutableListOf<Pair<String, Long>>()

        override fun permission(): LocationPermission = permission

        override fun isProviderEnabled(provider: String): Boolean = provider in enabled

        override fun lastKnown(provider: String): LocationFix? {
            if (deny) throw SecurityException("mock deny")
            return cached[provider]
        }

        override fun requestOnce(provider: String, timeoutMs: Long): LocationFix? {
            if (deny) throw SecurityException("mock deny")
            liveRequests += provider to timeoutMs
            return live[provider]
        }
    }

    private fun fix(
        provider: String = "gps",
        latitude: Double = 39.9041999,
        longitude: Double = 116.4073963,
        accuracy: Float = 12.5f,
        timestampMs: Long = 1_700_000_000_123L,
    ): LocationFix = LocationFix(latitude, longitude, accuracy, provider, timestampMs)

    private fun capability(source: LocationSource): LocationCapability = LocationCapability(source)

    private fun ok(source: LocationSource, vararg args: String): JsonObject =
        Json.parseToJsonElement(capability(source).run(args.toList())).jsonObject

    // ------------------------------------------------------------ 正常路径

    @Test
    fun `默认走 gps 且字段齐全`() {
        val source = FakeLocationSource(live = mapOf("gps" to fix()))
        val obj = ok(source)

        assertEquals(
            setOf("ok", "latitude", "longitude", "accuracy", "provider", "timestamp", "stale"),
            obj.keys,
            "成功 JSON 的字段是契约，不能多也不能少：$obj",
        )
        assertTrue(obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals(39.9041999, obj["latitude"]!!.jsonPrimitive.double, "坐标要原样输出，不许四舍五入")
        assertEquals(116.4073963, obj["longitude"]!!.jsonPrimitive.double)
        assertEquals(12.5, obj["accuracy"]!!.jsonPrimitive.double)
        assertEquals("gps", obj["provider"]!!.jsonPrimitive.content)
        assertEquals(1_700_000_000_123L, obj["timestamp"]!!.jsonPrimitive.long, "时间戳要用定位时刻")
        assertFalse(obj["stale"]!!.jsonPrimitive.boolean, "实时拿到的坐标不是 stale")
        assertEquals(listOf("gps"), source.liveRequests.map { it.first })
    }

    @Test
    fun `gps 没结果就回落 network`() {
        val source = FakeLocationSource(
            live = mapOf("network" to fix(provider = "network", accuracy = 800f)),
        )
        val obj = ok(source)

        assertEquals("network", obj["provider"]!!.jsonPrimitive.content, "provider 要写实际来源")
        assertFalse(obj["stale"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("gps", "network"), source.liveRequests.map { it.first }, "顺序必须是先 gps 再 network")
    }

    @Test
    fun `只授了大致位置也能取值`() {
        val source = FakeLocationSource(
            permission = LocationPermission.COARSE,
            live = mapOf("gps" to fix(accuracy = 2000f)),
        )
        val obj = ok(source)
        assertTrue(obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals(2000.0, obj["accuracy"]!!.jsonPrimitive.double)
    }

    // ------------------------------------------------------------ 超时 / 回落 / 失败

    @Test
    fun `全部超时回落最后已知位置并标 stale`() {
        val source = FakeLocationSource(
            live = emptyMap(),
            cached = mapOf("gps" to fix(timestampMs = 1_690_000_000_000L)),
        )
        val obj = ok(source)

        assertTrue(obj["stale"]!!.jsonPrimitive.boolean, "缓存来的坐标必须标 stale:true")
        assertEquals("gps", obj["provider"]!!.jsonPrimitive.content)
        assertEquals(1_690_000_000_000L, obj["timestamp"]!!.jsonPrimitive.long)
        assertTrue(source.liveRequests.isNotEmpty(), "先真的去请求过，才谈得上回落")
    }

    @Test
    fun `没有最后已知位置就失败且不编坐标`() {
        val source = FakeLocationSource(live = emptyMap(), cached = emptyMap())
        val failure = assertFailsWith<CapabilityFailure> { capability(source).run(emptyList()) }

        assertTrue(failure.message!!.contains("取不到位置"), "要说清是取不到：${failure.message}")
        assertTrue(failure.message!!.contains("最后已知"), "要说明回落也空了：${failure.message}")
        assertFalse(failure.message!!.contains("latitude"), "失败信息里不许出现假坐标")
    }

    @Test
    fun `失败经过注册表变成 ok false 加非零退出码`() {
        val registry = DeviceCapabilityRegistry(
            listOf(capability(FakeLocationSource(live = emptyMap(), cached = emptyMap()))),
        )
        val command = registry.parseWhole("termux-location")
        assertNotNull(command, "termux-location 要能被认成整条能力命令")

        val result = registry.execute(command)
        assertFalse(result.ok)
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("\"ok\":false"), "失败也要是 JSON：${result.output}")
        assertFalse(result.output.contains("latitude"), "失败输出里不能有坐标：${result.output}")
    }

    @Test
    fun `超时预算是总预算 两个 provider 分着用`() {
        val source = FakeLocationSource(live = emptyMap(), cached = mapOf("gps" to fix()))
        ok(source, "-t", "10000")

        assertEquals(2, source.liveRequests.size, "gps 超时后必须真的给 network 一次机会")
        // 两个 provider 平分 10 秒 ⇒ 第一次最多等 5 秒（不然 gps 会把预算吃干净）。
        assertTrue(
            source.liveRequests.first().second <= 5_000,
            "第一个 provider 不能独占总预算：${source.liveRequests}",
        )
        source.liveRequests.forEach { (_, ms) ->
            assertTrue(ms in 1..10_000, "单次等待不能超过总预算：${source.liveRequests}")
        }
    }

    // ------------------------------------------------------------ 权限 / provider

    @Test
    fun `没有权限要说清缺哪个 怎么授`() {
        val source = FakeLocationSource(permission = LocationPermission.NONE)
        val failure = assertFailsWith<CapabilityFailure> { capability(source).run(emptyList()) }
        val message = failure.message!!

        assertTrue(message.contains("ACCESS_FINE_LOCATION"), "要点名缺的权限：$message")
        assertTrue(message.contains("AndroidManifest"), "要说清单怎么声明：$message")
        assertTrue(message.contains("requestPermissions"), "要说运行时怎么申请：$message")
        assertTrue(source.liveRequests.isEmpty(), "没权限就不该发起定位请求")
    }

    @Test
    fun `provider 全不可用要说清是哪几个`() {
        val source = FakeLocationSource(enabled = emptySet(), cached = emptyMap())
        val failure = assertFailsWith<CapabilityFailure> { capability(source).run(emptyList()) }

        assertTrue(failure.message!!.contains("gps 不可用"), failure.message!!)
        assertTrue(failure.message!!.contains("network 不可用"), failure.message!!)
        assertTrue(source.liveRequests.isEmpty(), "provider 都关了就别浪费一次请求")
    }

    @Test
    fun `系统拒绝定位要翻译成人话`() {
        val source = FakeLocationSource(deny = true)
        val failure = assertFailsWith<CapabilityFailure> { capability(source).run(emptyList()) }

        assertTrue(failure.message!!.contains("SecurityException"), "要保留系统原因便于排查：${failure.message}")
        assertTrue(failure.message!!.contains("前台"), "要给出可照做的处置：${failure.message}")
        assertTrue(failure.message!!.contains("ACCESS_BACKGROUND_LOCATION"), "要说明后台定位不在能力范围内：${failure.message}")
    }

    // ------------------------------------------------------------ 参数语义

    @Test
    fun `r last 只读缓存 不发起请求`() {
        val source = FakeLocationSource(cached = mapOf("gps" to fix()))
        val obj = ok(source, "-r", "last")

        assertTrue(obj["stale"]!!.jsonPrimitive.boolean, "-r last 拿的本来就是缓存")
        assertTrue(source.liveRequests.isEmpty(), "-r last 不该发起实时请求")
    }

    @Test
    fun `显式指定 network 就不回落 gps`() {
        val source = FakeLocationSource(
            live = mapOf("gps" to fix()),  // gps 有值，但用户点名要 network
            cached = emptyMap(),
        )
        assertFailsWith<CapabilityFailure> { capability(source).run(listOf("-p", "network")) }
        assertEquals(listOf("network"), source.liveRequests.map { it.first }, "显式 -p 不许偷偷换源")
    }

    @Test
    fun `passive 是被动源 不主动请求`() {
        val source = FakeLocationSource(cached = mapOf("passive" to fix(provider = "passive")))
        val obj = ok(source, "-p", "passive")

        assertTrue(obj["stale"]!!.jsonPrimitive.boolean)
        assertEquals("passive", obj["provider"]!!.jsonPrimitive.content)
        assertTrue(source.liveRequests.isEmpty(), "passive 主动请求只会白等")
    }

    @Test
    fun `参数写错给用法而不是瞎跑`() {
        val source = FakeLocationSource()
        val bad = listOf(
            listOf("-p", "wifi"),
            listOf("-p"),
            listOf("-t", "abc"),
            listOf("-t", "0"),
            listOf("-t", "999999"),
            listOf("-r", "updates"),
            listOf("-i", "5000"),
            listOf("--nope"),
        )
        bad.forEach { args ->
            val failure = assertFailsWith<CapabilityFailure>("$args 应该被拒") {
                capability(source).run(args)
            }
            val message = failure.message!!
            assertTrue(
                message.contains("用法") || message.contains("不支持"),
                "$args 的报错要给用法或说明不支持：$message",
            )
        }
        assertTrue(source.liveRequests.isEmpty(), "参数错了就不该真的去定位")
    }

    // ------------------------------------------------------------ 契约对齐

    @Test
    fun `命令名与来源对齐 Termux`() {
        val cap = capability(FakeLocationSource())
        assertEquals("location", cap.name)
        assertEquals(CapabilityOrigin.TERMUX, cap.origin, "termux-location 属于 Termux 对齐的那批")
        assertEquals("termux-", cap.prefix)
        assertTrue(cap.usage.startsWith("termux-location"), cap.usage)
        assertTrue(cap.usage.contains("-r"), "用法里要写清模式：${cap.usage}")
        assertTrue(cap.usage.contains("-p"), "用法里要写清 provider：${cap.usage}")
        assertTrue(cap.usage.contains("-t"), "用法里要写清超时：${cap.usage}")
    }

    @Test
    fun `注册表按整条命令执行并回填 JSON`() {
        val registry = DeviceCapabilityRegistry(
            listOf(capability(FakeLocationSource(live = mapOf("gps" to fix())))),
        )
        val result = registry.execute(registry.parseWhole("termux-location -p gps")!!)

        assertTrue(result.ok, "应成功：${result.output}")
        assertEquals(0, result.exitCode)
        assertTrue(result.output.startsWith("{\"ok\":true"), "输出应是能力 JSON：${result.output}")
    }
}
