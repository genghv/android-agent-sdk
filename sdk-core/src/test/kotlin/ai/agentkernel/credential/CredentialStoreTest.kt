package ai.agentkernel.credential

import ai.agentkernel.device.CapabilityFailure
import ai.agentkernel.device.CapabilityOrigin
import ai.agentkernel.device.CapabilityResult
import ai.agentkernel.device.DeviceCapability
import ai.agentkernel.device.DeviceCapabilityRegistry
import ai.agentkernel.device.DeviceCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 凭据存储（模式 A）—— 纯 JVM。
 *
 * 这几条守的是**安全契约**，不是功能：
 * - **磁盘上没有明文**（加密不是为了好看，是为了模型读不到）；
 * - **解不开就抛异常**，绝不回退明文、绝不返回空串；
 * - **host 绑定**：key 只允许发给登记它的那个 host；
 * - **命令面永远不吐明文**（能 `get` 就等于把要防的东西送回模型）；
 * - **没有加密实现就安全失败**（不能「以为加密了、其实没加密」）。
 *
 * 测试用的 [FakeCipher] 只在测试代码里 —— **开源仓里没有加密实现**。
 */
class CredentialStoreTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "agentkernel-cred-${System.nanoTime()}").also { it.mkdirs() }

    private fun store(dir: File = tempDir(), cipher: CredentialCipher? = FakeCipher()): CredentialStore =
        CredentialStore(dir) { cipher ?: throw CredentialException("没有加密实现") }

    private fun capability(store: CredentialStore): DeviceCapability =
        AgentCredentialCapabilities(store).capabilities.single()

    private fun runViaRegistry(cap: DeviceCapability, vararg args: String): CapabilityResult {
        val registry = DeviceCapabilityRegistry(listOf(cap))
        return registry.execute(DeviceCommand("agent-", cap.name, args.toList(), 0, 0))
    }

    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @BeforeTest
    fun clearLoader() = CredentialCipherLoader.reset()

    @AfterTest
    fun resetLoader() = CredentialCipherLoader.reset()

    // ================= 契约 =================

    @Test
    fun `只做一条命令且是 SDK 自制的`() {
        val caps = AgentCredentialCapabilities(store()).capabilities
        assertEquals(1, caps.size, "只做一条命令 —— 提示词里每多一行就多占字符")
        val cap = caps.single()
        assertEquals("cred", cap.name)
        assertEquals(CapabilityOrigin.INTERNAL, cap.origin)
        assertEquals("agent-", cap.prefix, "内部自制的命令前缀是 agent-")
        assertTrue(cap.usage.startsWith("agent-cred "), "用法要带完整命令名：${cap.usage}")
        assertTrue(cap.usage.contains("list"), "用法行要带 list 子命令：${cap.usage}")
        assertTrue(cap.usage.contains("status"), "用法行要带 status 子命令：${cap.usage}")
    }

    @Test
    fun `短用法与真实用法一致`() {
        val cap = capability(store())
        // 核心模式列短用法、完整模式列用法 —— 两者都要带完整命令名。
        assertTrue(cap.shortUsage.startsWith("agent-cred "), "短用法也要带完整命令名：${cap.shortUsage}")
        assertTrue(cap.shortUsage.length < cap.usage.length, "短用法要比完整用法短")
    }

    // ================= 存 / 取 =================

    @Test
    fun `save 之后 load 拿回原文`() {
        val s = store()
        s.save("deepseek", "api.deepseek.com", "sk-abc123")
        assertEquals("sk-abc123", s.load("deepseek"))
    }

    @Test
    fun `磁盘上没有明文`() {
        val dir = tempDir()
        val s = store(dir)
        val plain = "sk-THIS-IS-A-SECRET-123456"
        s.save("deepseek", "api.deepseek.com", plain)

        val file = File(dir, "deepseek.cred.json")
        assertTrue(file.exists(), "凭据文件要被写出来")
        val text = file.readText()
        assertFalse(text.contains(plain), "★ 磁盘上不能出现明文")
        assertFalse(
            text.contains(Base64Codec.encode(plain.toByteArray())),
            "★ 磁盘上连明文的 base64 都不能出现",
        )
        // 但元数据（名字 / 目标 host）是在的 —— 那是宿主界面要显示的。
        assertTrue(text.contains("api.deepseek.com"), "目标 host 要能读到（不解密）")
    }

    @Test
    fun `凭据文件里只有元数据和密文`() {
        val dir = tempDir()
        val s = store(dir)
        s.save("deepseek", "api.deepseek.com", "sk-abc123")

        val obj = json(File(dir, "deepseek.cred.json").readText())
        assertEquals(1, obj["v"]!!.jsonPrimitive.content.toInt())
        assertEquals("deepseek", obj["name"]!!.jsonPrimitive.content)
        assertEquals("api.deepseek.com", obj["host"]!!.jsonPrimitive.content)
        val blob = obj["blob"]!!.jsonPrimitive.content
        assertNotEquals("sk-abc123", blob, "blob 里不能是明文")
        assertTrue(blob.isNotEmpty(), "blob 不能是空的")
    }

    @Test
    fun `load 不存在的凭据返回 null 而不是抛异常`() {
        assertNull(store().load("nope"), "没有就是没有 —— 不是失败")
    }

    @Test
    fun `名字本身不合法时 load 抛异常`() {
        // 名字不合法 = 宿主传了垃圾参数 ⇒ 要**大声**报出来，不能当成「没有」。
        assertFailsWith<CredentialException> { store().load("../../逃逸") }
    }

    @Test
    fun `同名覆盖后拿到的是新的`() {
        val s = store()
        s.save("k", "api.deepseek.com", "第一个")
        s.save("k", "api.deepseek.com", "第二个")
        assertEquals("第二个", s.load("k"))
        assertEquals(1, s.list().size, "同名覆盖不该产生第二个文件")
    }

    @Test
    fun `delete 之后就没有了`() {
        val s = store()
        s.save("k", "api.deepseek.com", "值")
        assertTrue(s.delete("k"))
        assertFalse(s.delete("k"), "删第二次返回 false")
        assertNull(s.load("k"))
    }

    // ================= host 绑定 =================

    @Test
    fun `host 对不上时 authFor 当场拒绝`() {
        val s = store()
        s.save("deepseek", "api.deepseek.com", "sk-abc123")

        val e = assertFailsWith<CredentialException> {
            s.authFor("deepseek", "https://api.openai.com/v1")
        }
        assertTrue(e.message!!.contains("api.deepseek.com"), "要说清允许发给谁：${e.message}")
        assertTrue(e.message!!.contains("api.openai.com"), "要说清被拒的是谁：${e.message}")
    }

    @Test
    fun `host 对得上时 authFor 给出 Bearer 头`() {
        val s = store()
        s.save("deepseek", "api.deepseek.com", "sk-abc123")

        val headers = s.authFor("deepseek", "https://api.deepseek.com/v1").headers()
        assertEquals("Bearer sk-abc123", headers["Authorization"])
    }

    @Test
    fun `host 比较忽略大小写和端口`() {
        val s = store()
        s.save("deepseek", "API.DeepSeek.COM", "sk-abc123")

        // 大小写不同但同一个 host ⇒ 允许。
        s.authFor("deepseek", "https://api.deepseek.com/v1")
        // 带端口 ⇒ 还是同一个 host ⇒ 允许。
        s.authFor("deepseek", "https://api.deepseek.com:443/v1")
    }

    @Test
    fun `凭据不存在时 authFor 报错`() {
        assertFailsWith<CredentialException> { store().authFor("没有这个", "https://api.deepseek.com/v1") }
    }

    // ================= host 绑定：fail-closed =================

    @Test
    fun `baseUrl 取不出 host 时 authFor 拒绝建客户端`() {
        val s = store()
        s.save("deepseek", "api.deepseek.com", "sk-abc123")

        // ⚠️ 这条守的是「**比不出来就不放行**」：旧实现是
        //    「两个 host 都取得到才比」⇒ 有一个取不到就**整个跳过比对** = 放行。
        for (bad in listOf("", "   ", "https://", "https://?x=1")) {
            assertFailsWith<CredentialException>("baseUrl=$bad 应该被拒") {
                s.authFor("deepseek", bad)
            }
        }
    }

    @Test
    fun `登记的 host 取不出来时 authFor 拒绝建客户端`() {
        val s = store()
        // 登记了一个**解析不出 host** 的目标（配置写坏了）。
        s.save("deepseek", "???", "sk-abc123")

        val e = assertFailsWith<CredentialException> {
            s.authFor("deepseek", "https://api.deepseek.com/v1")
        }
        assertTrue(e.message!!.contains("取不出来"), "要说清是「取不出 host」：${e.message}")
    }

    @Test
    fun `拒绝时的信息不回显 baseUrl 里的口令`() {
        val s = store()
        s.save("deepseek", "api.deepseek.com", "sk-abc123")

        // URL 的 userinfo 里可能带口令（`https://user:口令@host`）⇒
        // 异常信息会进日志/转录 ⇒ **不许回显 baseUrl**。
        val e = assertFailsWith<CredentialException> {
            s.authFor("deepseek", "https://user:s3cr3t@")
        }
        assertFalse(e.message!!.contains("s3cr3t"), "异常信息不许回显 URL 里的口令：${e.message}")
    }

    // ================= 解不开就失败 =================

    @Test
    fun `密文被改过 load 抛异常而不是返回明文`() {
        val dir = tempDir()
        val s = store(dir)
        s.save("k", "api.deepseek.com", "sk-abc123")

        // 把 blob 换成别的字节 ⇒ 模拟密文被篡改。
        val file = File(dir, "k.cred.json")
        val obj = json(file.readText())
        file.writeText(
            buildJsonObject {
                obj.forEach { (key, value) ->
                    if (key == "blob") put(key, Base64Codec.encode(byteArrayOf(1, 2, 3, 4)))
                    else put(key, value)
                }
            }.toString()
        )

        assertFailsWith<CredentialException> { s.load("k") }
    }

    @Test
    fun `换名字的密文文件 load 抛异常`() {
        val dir = tempDir()
        val s = store(dir)
        s.save("a", "api.deepseek.com", "sk-abc123")

        // 把 a 的密文文件改名成 b —— 文件里的 name 还是 a ⇒ 对不上 ⇒ 拒绝。
        File(dir, "a.cred.json").renameTo(File(dir, "b.cred.json"))

        assertFailsWith<CredentialException> { s.load("b") }
    }

    @Test
    fun `没有加密实现时 load 抛异常而不是空串`() {
        val dir = tempDir()
        // 先用一个有实现的 store 写一条（模拟「以前写好的」）。
        store(dir).save("k", "api.deepseek.com", "sk-abc123")

        val s = store(dir, cipher = null)
        val e = assertFailsWith<CredentialException> { s.load("k") }
        assertTrue(e.message!!.contains("没有加密实现"), "要说清是缺实现：${e.message}")
    }

    @Test
    fun `没有加密实现时 save 也要抛异常而不是写明文`() {
        val dir = tempDir()
        val s = store(dir, cipher = null)
        assertFailsWith<CredentialException> { s.save("k", "api.deepseek.com", "sk-abc123") }
        assertFalse(File(dir, "k.cred.json").exists(), "★ 没加密成功就不能留下明文文件")
    }

    @Test
    fun `没有加密实现时 list 和 hostOf 仍然能用`() {
        val dir = tempDir()
        // 先用一个有实现的 store 写一条（模拟「以前写好的」）。
        store(dir).save("k", "api.deepseek.com", "sk-abc123")

        val noCipher = store(dir, cipher = null)
        assertEquals(listOf("k"), noCipher.list(), "列凭据不需要解密")
        assertEquals("api.deepseek.com", noCipher.hostOf("k"), "读 host 不需要解密")
        assertFalse(noCipher.isDecryptable(), "没有实现 ⇒ 解不开")
    }

    // ================= 凭据名白名单 =================

    @Test
    fun `路径穿越的名字被拒绝`() {
        val dir = tempDir()
        val s = store(dir)
        val e = assertFailsWith<CredentialException> { s.save("../../逃逸", "api.deepseek.com", "sk-abc") }
        assertTrue(e.message!!.contains("不合法"), "要说清是名字不合法：${e.message}")
        assertEquals(0, dir.listFiles()?.size ?: 0, "不能写出目录外的文件")
    }

    @Test
    fun `带路径分隔符和空格的名字被拒绝`() {
        val s = store()
        listOf("a/b", "a\\b", "..", ".", "有空格", "以点开头.x").forEach { bad ->
            assertFailsWith<CredentialException>("名字「$bad」必须被拒绝") {
                s.save(bad, "api.deepseek.com", "sk-abc")
            }
        }
    }

    @Test
    fun `list 只列合法名`() {
        val dir = tempDir()
        val s = store(dir)
        s.save("good-name_1", "api.deepseek.com", "值")
        // 目录里被塞进一个名字不合规的文件（agent 有 shell 权限，做得到）。
        File(dir, "坏名字!.cred.json").writeText("{}")

        assertEquals(listOf("good-name_1"), s.list())
    }

    // ================= 迁移 =================

    @Test
    fun `迁移是先写新再删旧`() {
        val dir = tempDir()
        val legacy = File(dir, "legacy.txt").apply { writeText("sk-legacy-key") }
        val s = store(dir)

        assertTrue(s.migratePlaintext(legacy, "deepseek", "api.deepseek.com"))
        assertFalse(legacy.exists(), "旧文件该被删掉")
        assertEquals("sk-legacy-key", s.load("deepseek"), "新文件要能解出原文")
        assertFalse(File(dir, "deepseek.cred.json").readText().contains("sk-legacy-key"), "★ 新文件里不能有明文")
    }

    @Test
    fun `迁移校验没过时旧文件要保留`() {
        val dir = tempDir()
        val legacy = File(dir, "legacy.txt").apply { writeText("sk-legacy-key") }
        // 一个「解出来是别的」的假实现 ⇒ 校验必然不过。
        val liar = object : CredentialCipher {
            override fun seal(plain: ByteArray, aad: ByteArray): ByteArray = plain
            override fun open(sealed: ByteArray, aad: ByteArray): ByteArray = "别的内容".toByteArray()
        }
        val s = store(dir, cipher = liar)

        assertFailsWith<CredentialException> { s.migratePlaintext(legacy, "deepseek", "api.deepseek.com") }
        assertTrue(legacy.exists(), "★ 校验没过就不能删旧文件（宁可多留一份明文）")
    }

    @Test
    fun `迁移时旧文件不存在就返回 false`() {
        assertFalse(store().migratePlaintext(File(tempDir(), "没有这个.txt"), "k", "api.deepseek.com"))
    }

    // ================= 加载器 =================

    @Test
    fun `没注册实现时 resolve 抛异常（安全失败）`() {
        val e = assertFailsWith<CredentialException> { CredentialCipherLoader.resolve() }
        assertTrue(e.message!!.contains("未注入"), "要说清是没注入：${e.message}")
        assertFalse(CredentialCipherLoader.isAvailable())
    }

    @Test
    fun `注册实例后 resolve 返回它`() {
        val fake = FakeCipher()
        CredentialCipherLoader.register(fake)
        assertTrue(CredentialCipherLoader.isAvailable())
        assertSame(fake, CredentialCipherLoader.resolve())
    }

    @Test
    fun `注册类名后 resolve 能实例化`() {
        CredentialCipherLoader.register("ai.agentkernel.credential.PublicFakeCipher")
        assertTrue(CredentialCipherLoader.resolve() is PublicFakeCipher)
    }

    @Test
    fun `resolve 会缓存实例`() {
        CredentialCipherLoader.register("ai.agentkernel.credential.PublicFakeCipher")
        val first = CredentialCipherLoader.resolve()
        val second = CredentialCipherLoader.resolve()
        assertSame(first, second, "按类名反射出来的实例是有状态的，不能每次 new 一个")
    }

    @Test
    fun `注册不存在的类名 resolve 抛异常`() {
        CredentialCipherLoader.register("no.such.Clazz")
        assertFailsWith<CredentialException> { CredentialCipherLoader.resolve() }
    }

    @Test
    fun `注册不是 CredentialCipher 的类 resolve 抛异常`() {
        CredentialCipherLoader.register("ai.agentkernel.credential.NotACipher")
        val e = assertFailsWith<CredentialException> { CredentialCipherLoader.resolve() }
        assertTrue(e.message!!.contains("CredentialCipher"), "要说清类型不对：${e.message}")
    }

    @Test
    fun `reset 之后回到未注入状态`() {
        CredentialCipherLoader.register(FakeCipher())
        CredentialCipherLoader.reset()
        assertFalse(CredentialCipherLoader.isAvailable())
        assertFailsWith<CredentialException> { CredentialCipherLoader.resolve() }
    }

    // ================= 命令面 =================

    @Test
    fun `list 不吐明文`() {
        val s = store()
        s.save("deepseek", "api.deepseek.com", "sk-SUPERSECRET")

        val out = capability(s).run(listOf("list"))
        assertFalse(out.contains("SUPERSECRET"), "★ 命令面绝不能返回明文")
        val obj = json(out)
        val first = obj["credentials"]!!.jsonArray[0].jsonObject
        assertEquals("deepseek", first["name"]!!.jsonPrimitive.content)
        assertEquals("api.deepseek.com", first["host"]!!.jsonPrimitive.content)
    }

    @Test
    fun `status 不吐明文`() {
        val s = store()
        s.save("deepseek", "api.deepseek.com", "sk-SUPERSECRET")

        val out = capability(s).run(listOf("status", "deepseek"))
        assertFalse(out.contains("SUPERSECRET"), "★ 命令面绝不能返回明文")
        val obj = json(out)
        assertEquals("deepseek", obj["name"]!!.jsonPrimitive.content)
        assertEquals("api.deepseek.com", obj["host"]!!.jsonPrimitive.content)
        assertEquals("present", obj["state"]!!.jsonPrimitive.content)
        assertEquals(true, obj["decryptable"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `status 在没有加密实现时报 decryptable 为 false`() {
        val dir = tempDir()
        store(dir).save("deepseek", "api.deepseek.com", "sk-abc")
        val out = capability(store(dir, cipher = null)).run(listOf("status", "deepseek"))
        assertEquals(false, json(out)["decryptable"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `命令面没有取值的子命令`() {
        val cap = capability(store())
        // 「能 get 出明文」就等于把要防的东西送回模型 ⇒ 这几个必须不存在。
        listOf("get", "read", "show", "cat", "dump", "value").forEach { sub ->
            assertFailsWith<CapabilityFailure>("子命令「$sub」必须不存在") { cap.run(listOf(sub, "deepseek")) }
        }
    }

    @Test
    fun `缺参数和不认识的子命令要报错`() {
        val cap = capability(store())
        assertFailsWith<CapabilityFailure> { cap.run(emptyList()) }
        assertFailsWith<CapabilityFailure> { cap.run(listOf("status")) }
        val e = assertFailsWith<CapabilityFailure> { cap.run(listOf("frobnicate")) }
        assertTrue(e.message!!.contains("agent-cred"), "用法错误要说清怎么用：${e.message}")
    }

    @Test
    fun `status 未知名字报错`() {
        val cap = capability(store())
        val e = assertFailsWith<CapabilityFailure> { cap.run(listOf("status", "没有这个")) }
        assertTrue(e.message!!.contains("没有这个"), "要说清是哪个：${e.message}")
    }

    @Test
    fun `经注册表跑失败会转成 ok false 与非零退出码`() {
        val cap = capability(store())
        val r = runViaRegistry(cap, "status", "没有这个")
        assertFalse(r.ok)
        assertTrue(r.exitCode != 0, "失败要非零退出码")
        assertFalse(json(r.output)["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    // ================= 宿主侧 API =================

    @Test
    fun `宿主侧 API 能写能读能删`() {
        val s = store()
        val caps = AgentCredentialCapabilities(s)
        caps.setSecret("deepseek", "api.deepseek.com", "sk-abc123")
        assertEquals("sk-abc123", caps.secretFor("deepseek"))
        assertEquals(listOf("deepseek"), caps.listSecrets())
        assertTrue(caps.deleteSecret("deepseek"))
        assertNull(caps.secretFor("deepseek"))
    }
}

/**
 * 测试用的假加密实现 —— **只在测试代码里**。
 *
 * 它模拟真实现的关键性质：
 * - **绑 AAD**：AAD 变了就解不开（对应真实现里的 AEAD 附加认证数据）；
 * - **验完整性**：密文被改一个字节就解不开。
 *
 * ⚠️ **它不是加密**（XOR + SHA-256），别拿它做任何真实用途。
 */
internal class FakeCipher : CredentialCipher {
    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val tag = sha256(aad + plain).copyOf(4)
        val key = sha256(aad)
        val body = ByteArray(plain.size) { (plain[it].toInt() xor key[it % key.size].toInt()).toByte() }
        return tag + body
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size < 4) throw CredentialException("密文包太短")
        val tag = sealed.copyOfRange(0, 4)
        val body = sealed.copyOfRange(4, sealed.size)
        val key = sha256(aad)
        val plain = ByteArray(body.size) { (body[it].toInt() xor key[it % key.size].toInt()).toByte() }
        if (!sha256(aad + plain).copyOf(4).contentEquals(tag)) {
            throw CredentialException("解不开：AAD 不匹配或密文被改过")
        }
        return plain
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}

/** 给「按类名注册」那条测试用的公开实现（反射需要一个公开的无参构造）。 */
class PublicFakeCipher : CredentialCipher {
    private val inner = FakeCipher()
    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray = inner.seal(plain, aad)
    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray = inner.open(sealed, aad)
}

/** 给「注册的不是 CredentialCipher」那条测试用的类。 */
class NotACipher
