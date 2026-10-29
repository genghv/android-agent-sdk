package ai.agentkernel.credential

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Base64 —— **自己实现的那一份**（见 [Base64Codec] 为什么不能用 `java.util.Base64`）。
 *
 * 守两件事：
 * 1. **与标准逐字一致**（RFC 4648 §4）—— 否则别的工具解不开；
 * 2. **解码严格** —— 遇到非法输入就抛，**绝不尽力解一半**
 *    （半解出来的密文比解不开更危险：会被当成真密文用）。
 */
class Base64CodecTest {

    @Test
    fun `标准向量`() {
        // RFC 4648 §10 的那组标准测试向量。
        assertEquals("", Base64Codec.encode("".toByteArray()))
        assertEquals("Zg==", Base64Codec.encode("f".toByteArray()))
        assertEquals("Zm8=", Base64Codec.encode("fo".toByteArray()))
        assertEquals("Zm9v", Base64Codec.encode("foo".toByteArray()))
        assertEquals("Zm9vYg==", Base64Codec.encode("foob".toByteArray()))
        assertEquals("Zm9vYmE=", Base64Codec.encode("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", Base64Codec.encode("foobar".toByteArray()))
    }

    @Test
    fun `标准向量反向解`() {
        assertContentEquals("".toByteArray(), Base64Codec.decode(""))
        assertContentEquals("f".toByteArray(), Base64Codec.decode("Zg=="))
        assertContentEquals("fo".toByteArray(), Base64Codec.decode("Zm8="))
        assertContentEquals("foo".toByteArray(), Base64Codec.decode("Zm9v"))
        assertContentEquals("foob".toByteArray(), Base64Codec.decode("Zm9vYg=="))
        assertContentEquals("fooba".toByteArray(), Base64Codec.decode("Zm9vYmE="))
        assertContentEquals("foobar".toByteArray(), Base64Codec.decode("Zm9vYmFy"))
    }

    @Test
    fun `全部 256 个字节值往返`() {
        val all = ByteArray(256) { it.toByte() }
        assertContentEquals(all, Base64Codec.decode(Base64Codec.encode(all)))
    }

    @Test
    fun `各种长度都往返`() {
        val source = "凭据密文包-0123456789-abcdefghijklmnopqrstuvwxyz"
        for (len in 0..source.length) {
            val bytes = source.substring(0, len).toByteArray()
            assertContentEquals(bytes, Base64Codec.decode(Base64Codec.encode(bytes)), "长度 $len 要能往返")
        }
    }

    @Test
    fun `长度不是 4 的倍数要报错`() {
        assertFailsWith<CredentialException> { Base64Codec.decode("Zg=") }
        assertFailsWith<CredentialException> { Base64Codec.decode("Zm9vYg=") }
    }

    @Test
    fun `有非法字符要报错`() {
        assertFailsWith<CredentialException> { Base64Codec.decode("Zm9v!mFy") }
        assertFailsWith<CredentialException> { Base64Codec.decode("中文中文中文中文") }
    }

    @Test
    fun `填充位置不对要报错`() {
        assertFailsWith<CredentialException> { Base64Codec.decode("Zg==Zg==") }
        assertFailsWith<CredentialException> { Base64Codec.decode("Z=9v") }
    }

    @Test
    fun `编码结果里不含空白或换行`() {
        // 编码结果要能原样进 JSON —— 换行会让 JSON 变成多行（能编，但不该发生）。
        val encoded = Base64Codec.encode(ByteArray(300) { it.toByte() })
        assertEquals(-1, encoded.indexOf('\n'))
        assertEquals(-1, encoded.indexOf('\r'))
        assertEquals(-1, encoded.indexOf(' '))
    }
}
