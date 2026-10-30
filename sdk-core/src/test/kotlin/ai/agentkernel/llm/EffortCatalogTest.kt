package ai.agentkernel.llm

import ai.agentkernel.llm.openai.EffortCatalog
import ai.agentkernel.llm.openai.EffortCatalogClient
import ai.agentkernel.llm.openai.EffortCatalogException
import ai.agentkernel.llm.openai.EffortProfileSource
import ai.agentkernel.llm.openai.GetTransport
import ai.agentkernel.llm.openai.ReasoningWire
import ai.agentkernel.llm.openai.VendorEffortProfile
import ai.agentkernel.llm.openai.VendorEffortProfiles
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 档位清单「拉不到 ⇒ 退回内置表」这条路径的契约。
 *
 * ## 为什么这组测试必须有
 *
 * 档位清单是**锦上添花**，不是**启动前提**（用户定的口径：
 * 「云端不依赖云端可得性」）⇒ 服务端挂了 / 报文不认 / 强度编码非法，
 * **宿主照样要起得来**。
 *
 * 这几条守的就是：
 * - [EffortCatalogClient.fetchOrNull] 拉不到 ⇒ **返回 null**（不是抛异常）；
 * - [EffortCatalogClient.fetch] 的行为**一个字都没变**（仍然抛）；
 * - 拉不到时 [VendorEffortProfiles.resolve] 落回 **BUILTIN**（不是空表、不是崩）。
 *
 * ⚠️ 末尾用 `assertFailsWith` 的那条**显式标了 `: Unit`** ——
 * 表达式体的最后一个表达式决定方法的返回类型，而 JUnit 5 对**非 void** 的 `@Test`
 * **直接忽略**（不执行、也不报错）⇒ 必须返回 void（见 `TestVoidReturnTest`）。
 */
class EffortCatalogTest {

    /** 服务端给的一份清单（强度编码必须是 2 的幂）。 */
    private val serverBody = """
        {
          "model": "glm-4.6",
          "levels": [
            {"strength": 1, "key": "off", "label": "关闭思考", "hint": "不发 effort", "kind": "off", "index": 0, "bits": "0b1"},
            {"strength": 8, "key": "high", "label": "高", "hint": "reasoning_strength=8", "kind": "effort", "index": 1, "bits": "0b1000"}
          ],
          "default_strength": 8,
          "default_key": "high"
        }
    """.trimIndent()

    private fun client(transport: GetTransport) =
        EffortCatalogClient(baseUrl = "https://gw.example.com", transport = transport)

    // ---- fetchOrNull：拉不到返回 null ----

    @Test
    fun `拉不到时 fetchOrNull 返回 null 而不是抛异常`() = runBlocking {
        val transport = FakeGetTransport(error = IllegalStateException("connect failed"))
        val client = client(transport)

        val catalog = client.fetchOrNull()

        assertNull(catalog, "拉不到 ⇒ 返回 null（不让宿主起不来）")
        assertEquals(1, transport.calls, "该拉一次就拉一次")
    }

    @Test
    fun `报文不认时 fetchOrNull 也返回 null`() = runBlocking {
        val transport = FakeGetTransport(body = "这不是 JSON")
        val client = client(transport)

        assertNull(client.fetchOrNull(), "解析失败 ⇒ 返回 null")
    }

    @Test
    fun `拉得到时 fetchOrNull 返回解析好的清单`() = runBlocking {
        val transport = FakeGetTransport(body = serverBody)
        val client = client(transport)

        val catalog = client.fetchOrNull()

        assertNotNull(catalog, "拉得到就不该是 null")
        assertEquals("glm-4.6", catalog.model)
        assertEquals(2, catalog.levels.size)
        assertEquals(8, catalog.defaultStrength)
        assertEquals("high", catalog.defaultKey)
        assertEquals(listOf(1, 8), catalog.levels.map { it.strength })
        assertEquals("off", catalog.levels.first().key)
        assertTrue(catalog.supportsThinking)
    }

    // ---- fetch 的行为一个字都没变 ----

    @Test
    fun `fetch 仍然抛 EffortCatalogException`(): Unit = runBlocking {
        val transport = FakeGetTransport(error = IllegalStateException("connect failed"))
        val client = client(transport)

        assertFailsWith<EffortCatalogException> { client.fetch() }
    }

    @Test
    fun `fetch 成功时照旧返回清单`() = runBlocking {
        val client = client(FakeGetTransport(body = serverBody))

        val catalog = client.fetch()

        assertEquals("glm-4.6", catalog.model)
        assertEquals(2, catalog.levels.size)
    }

    // ---- 三态来源：USER > SERVER > BUILTIN ----

    @Test
    fun `服务端为 null 时落回 BUILTIN`() = runBlocking {
        // 服务端拉不到（fetchOrNull 返回 null）⇒ 落回内置表。
        val catalog = client(FakeGetTransport(error = IllegalStateException("connect failed"))).fetchOrNull()
        assertNull(catalog)

        val profile = VendorEffortProfiles.resolve(ReasoningWire.GATEWAY, server = catalog)

        assertEquals(EffortProfileSource.BUILTIN, profile.source, "没有服务端清单 ⇒ 内置表")
        assertEquals(
            VendorEffortProfiles.GATEWAY.levels,
            profile.levels,
            "落回内置表 ⇒ 档位就是内置表那份",
        )
        assertTrue(profile.supportsThinking, "内置表本来就是有档位的")
    }

    @Test
    fun `有服务端清单时用服务端那份并标成 SERVER`() = runBlocking {
        val catalog = client(FakeGetTransport(body = serverBody)).fetchOrNull()
        assertNotNull(catalog)

        val profile = VendorEffortProfiles.resolve(ReasoningWire.GATEWAY, server = catalog)

        assertEquals(EffortProfileSource.SERVER, profile.source, "有服务端清单 ⇒ 以服务端为准")
        assertEquals(catalog.levels, profile.levels, "档位必须来自服务端那份")
        assertEquals("glm-4.6", profile.model)
        assertEquals(catalog.defaultStrength, profile.defaultStrength)
        assertTrue(
            profile.levels.none { it.key == "max" },
            "服务端只给了 off/high ⇒ 不该多出内置表的 max：${profile.levels.map { it.key }}",
        )
    }

    @Test
    fun `宿主自己给了就直接用宿主的`() = runBlocking {
        val hostProfile = VendorEffortProfile(
            vendor = "my-gateway",
            levels = emptyList(),
            source = EffortProfileSource.USER,
        )

        val profile = VendorEffortProfiles.resolve(
            ReasoningWire.GATEWAY,
            server = null,
            user = hostProfile,
        )

        assertSame(hostProfile, profile, "宿主给的档案直接用（USER > SERVER > BUILTIN）")
        assertEquals(EffortProfileSource.USER, profile.source)
    }

    @Test
    fun `宿主自己给了就压过服务端清单`() = runBlocking {
        val catalog = client(FakeGetTransport(body = serverBody)).fetchOrNull()
        assertNotNull(catalog)
        val hostProfile = VendorEffortProfile(
            vendor = "my-gateway",
            levels = emptyList(),
            source = EffortProfileSource.USER,
        )

        val profile = VendorEffortProfiles.resolve(
            ReasoningWire.GATEWAY,
            server = catalog,
            user = hostProfile,
        )

        assertSame(hostProfile, profile, "USER 压过 SERVER")
        assertEquals(EffortProfileSource.USER, profile.source)
    }
}

/** 假 GET 传输：要么回一份报文，要么抛异常。 */
private class FakeGetTransport(
    private val body: String? = null,
    private val error: Throwable? = null,
) : GetTransport {
    var calls: Int = 0
        private set

    override suspend fun get(url: String, headers: Map<String, String>): String {
        calls++
        error?.let { throw it }
        return body.orEmpty()
    }
}
