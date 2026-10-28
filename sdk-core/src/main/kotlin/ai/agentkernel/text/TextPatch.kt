package ai.agentkernel.text

/**
 * 补丁失败（锚点找不到 / 行号越界 / 规格写错）。
 *
 * 消息是写给 **agent** 看的：它拿到这条消息后要能自己改对再来一次，
 * 所以每条消息都得说清「哪里不对」+「文件长什么样」（总行数 / 候选行号）。
 */
class TextPatchException(message: String) : Exception(message)

/** 一次文本补丁的操作。 */
sealed interface TextPatch {
    /** 替换 1 基行号 [startLine]..[endLine] 这段（含端点）为 [text]。 */
    data class ReplaceLines(val startLine: Int, val endLine: Int, val text: String) : TextPatch

    /**
     * 按**锚点**替换：把「包含 [anchor] 的那一行（可多行）」整块换成 [newText]。
     * 锚点不唯一（多处命中）⇒ 抛 [TextPatchException]（要求模型把锚点写得更具体）。
     */
    data class ReplaceBlock(val anchor: String, val newText: String) : TextPatch

    /** 在第 [line] 行**之后**插入 [text]。 */
    data class InsertAfter(val line: Int, val text: String) : TextPatch

    /** 删除第 [line] 行。 */
    data class DeleteLine(val line: Int) : TextPatch
}

/**
 * 行级文本补丁 —— agent 改文本文件不用重写整个文件。
 *
 * ## 为什么要有这层
 *
 * agent 改一个几百行的文件时，整文件重写有三个老问题：token 贵、写错一行就毁全文、
 * 出错后说不清「到底改了哪儿」。行级补丁把「改哪儿」变成**行号**或**锚点**，
 * 改完还能用 [describe] 告诉它「第几行变成了什么」，它自己就能核对。
 *
 * ## 三个约定
 *
 * 1. **行号 1 基**（跟人读文件一样，也跟 agent 数行号一样）。
 * 2. **锚点必须唯一**：命中 0 处或多处都报错 —— 猜哪一处就是静默改坏文件。
 * 3. **行尾风格跟原文走**：原文是 CRLF 就还是 CRLF，补丁里的 `\n` 会按原文拼回去。
 */
object TextPatcher {

    /** 应用补丁。失败抛 [TextPatchException]（消息要说清为什么）。 */
    fun apply(original: String, patch: TextPatch): String {
        val doc = splitDoc(original)
        val patched = when (patch) {
            is TextPatch.ReplaceLines -> replaceLines(doc, patch)
            is TextPatch.ReplaceBlock -> replaceBlock(doc, patch)
            is TextPatch.InsertAfter -> insertAfter(doc, patch)
            is TextPatch.DeleteLine -> deleteLine(doc, patch)
        }
        return patched.render()
    }

    /**
     * 解析命令行式的补丁规格（agent 用的就是这一套）。
     *
     * 规格形如：`replace-block <anchor> <newText>` / `replace-lines <start> <end> <newText>`
     * / `insert-after <line> <newText>` / `delete-line <line>`
     *
     * ★ 参数里的 `\n` 表示**换行**（shell 的引号里传不了真换行，统一用转义）。
     */
    fun parse(spec: String): TextPatch {
        val s = spec.trim()
        if (s.isEmpty()) {
            throw TextPatchException(
                "补丁规格为空。可用命令：replace-block / replace-lines / insert-after / delete-line"
            )
        }
        return when {
            s.startsWith("replace-block") -> parseReplaceBlock(s)
            s.startsWith("replace-lines") -> parseReplaceLines(s)
            s.startsWith("insert-after") -> parseInsertAfter(s)
            s.startsWith("delete-line") -> parseDeleteLine(s)
            else -> throw TextPatchException(
                "不认识的补丁命令：${s.take(20)}。可用命令：replace-block / replace-lines / insert-after / delete-line"
            )
        }
    }

    /** 行级 diff 描述：返回「改了哪几行」的短文本（给 agent 看，确认它改对了）。 */
    fun describe(original: String, patched: String): String {
        if (original == patched) return NO_CHANGE
        val a = splitDoc(original).lines
        val b = splitDoc(patched).lines

        // 只做「公共前缀 + 公共后缀」的最简 diff —— 补丁本来就是行级的，
        // 真要 LCS 才能算准的差异说明补丁本身改得太野了，描述个大概就够 agent 核对。
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (
            suffix < a.size - prefix &&
            suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) {
            suffix++
        }

        val oldCount = a.size - prefix - suffix
        val newCount = b.size - prefix - suffix
        if (oldCount == 0 && newCount == 0) return NO_CHANGE

        val firstOld = prefix + 1                 // 1 基
        val lastOld = prefix + oldCount
        return when {
            // 纯插入：原文第 prefix 行之后多了几行
            oldCount == 0 && prefix == 0 -> "第 1 行前插入 $newCount 行"
            oldCount == 0 -> "第 $prefix 行后插入 $newCount 行"
            // 纯删除
            newCount == 0 -> "删除第 ${lineRange(firstOld, lastOld)} 行"
            // 一行换一行
            oldCount == 1 && newCount == 1 -> "改了第 $firstOld 行"
            else -> "第 ${lineRange(firstOld, lastOld)} 行 → $newCount 行"
        }
    }

    // ---- 规格解析 ----

    /**
     * `replace-block <锚点>|<新内容>`
     *
     * ★ 必须在第一个 `|` 处切：锚点可能是多词文本（没法用空白定边界），
     * 而新内容里**允许**再出现 `|`（只在第一个 `|` 处切）。
     *
     * 没有 `|` 就报错并给示例 —— 宁可让它重写，也别猜边界把文件改坏。
     */
    private fun parseReplaceBlock(spec: String): TextPatch {
        var rest = spec.removePrefix("replace-block").trimStart()
        // 允许 `replace-block|锚点|新内容`（命令词后面直接跟竖线，这是 shell 里最常写的形式）
        if (rest.startsWith("|")) rest = rest.substring(1)
        val sep = rest.indexOf('|')
        if (sep < 0) {
            throw TextPatchException(
                "replace-block 需要用 | 分隔锚点和新内容，例如：replace-block|旧的标题|新的标题"
            )
        }
        val anchor = rest.substring(0, sep).trim()
        val newText = rest.substring(sep + 1)
        if (anchor.isEmpty()) {
            throw TextPatchException(
                "replace-block 的锚点不能为空，例如：replace-block|旧的标题|新的标题"
            )
        }
        return TextPatch.ReplaceBlock(decodeEscapes(anchor), decodeEscapes(newText))
    }

    /**
     * `replace-lines <start> <end> <新内容>`
     *
     * 空白切前两个 token 当行号，**剩余全部**（含空格）都是新内容 ——
     * 内容里的空格不能被吃掉，那是被替换的正文的一部分。
     */
    private fun parseReplaceLines(spec: String): TextPatch {
        val rest = spec.removePrefix("replace-lines").trimStart()
        val m = TWO_NUM_ARGS.find(rest)
            ?: throw TextPatchException(
                "replace-lines 的写法是：replace-lines <起始行> <结束行> <新内容>，例如：replace-lines 3 4 新的第四行"
            )
        val start = m.groupValues[1].toIntOrNull()
            ?: throw TextPatchException("replace-lines 的起始行号必须是整数：${m.groupValues[1]}")
        val end = m.groupValues[2].toIntOrNull()
            ?: throw TextPatchException("replace-lines 的结束行号必须是整数：${m.groupValues[2]}")
        val text = m.groupValues.getOrElse(3) { "" }
        return TextPatch.ReplaceLines(start, end, decodeEscapes(text))
    }

    /** `insert-after <line> <新内容>` —— 空白切第一个 token 当行号，剩余全部是新内容。 */
    private fun parseInsertAfter(spec: String): TextPatch {
        val rest = spec.removePrefix("insert-after").trimStart()
        val m = ONE_NUM_ARGS.find(rest)
            ?: throw TextPatchException(
                "insert-after 的写法是：insert-after <行号> <新内容>，例如：insert-after 3 补一行说明"
            )
        val line = m.groupValues[1].toIntOrNull()
            ?: throw TextPatchException("insert-after 的行号必须是整数：${m.groupValues[1]}")
        val text = m.groupValues.getOrElse(2) { "" }
        return TextPatch.InsertAfter(line, decodeEscapes(text))
    }

    /** `delete-line <line>` —— 整串去空白后必须能解析成整数。 */
    private fun parseDeleteLine(spec: String): TextPatch {
        val rest = spec.removePrefix("delete-line").trim()
        val line = rest.toIntOrNull()
            ?: throw TextPatchException("delete-line 的行号必须是整数：$rest")
        return TextPatch.DeleteLine(line)
    }

    /**
     * 参数里的 `\n` 表示真换行；**其余反斜杠原样保留**（`\\`、`\t` 都不转义）。
     *
     * 只认 `\n` 这一个转义 —— shell 引号里传不了真换行，别的转义没人用，
     * 多认几个反而会把正文里的路径（`C:\new`）改坏。
     */
    private fun decodeEscapes(s: String): String = s.replace("\\n", "\n")

    // ---- 补丁执行 ----

    /** 替换 1 基行号 [start]..[end]（含端点）那几行；[end] < [start] 视为单行替换。 */
    private fun replaceLines(doc: Doc, patch: TextPatch.ReplaceLines): Doc {
        val start = patch.startLine
        val end = if (patch.endLine < patch.startLine) patch.startLine else patch.endLine
        if (start < 1) {
            throw TextPatchException("行号必须从 1 开始：第 $start 行")
        }
        if (end > doc.lineCount) {
            throw TextPatchException(
                "行号越界：第 $end 行不存在，文件共 ${doc.lineCount} 行"
            )
        }
        val content = splitContent(patch.text)
        // 内容为空 ⇒ 这段被删掉了（和「替换成空」是同一件事）
        val lines = doc.lines.take(start - 1) + content + doc.lines.drop(end)
        return doc.copy(lines = lines)
    }

    /** 按锚点替换整块：命中 0 处或多处都报错（猜哪一处就是静默改坏文件）。 */
    private fun replaceBlock(doc: Doc, patch: TextPatch.ReplaceBlock): Doc {
        val anchor = normalizeAnchor(patch.anchor)
        if (anchor.isEmpty()) {
            throw TextPatchException("replace-block 的锚点不能为空，例如：replace-block|旧的标题|新的标题")
        }
        val hits = findAnchorHits(doc.lines, anchor)
        if (hits.isEmpty()) {
            throw TextPatchException(
                "锚点没找到：${anchor.take(40)}（文件共 ${doc.lineCount} 行）。锚点要和原文里的字完全一致。"
            )
        }
        if (hits.size > 1) {
            val at = hits.joinToString("、") { (it.first + 1).toString() }
            throw TextPatchException(
                "锚点命中 ${hits.size} 处，行号：$at。请把锚点写得更具体（多带几行上下文），让它只剩一处。"
            )
        }
        val (from, to) = hits.first()
        val content = splitContent(patch.newText)
        val lines = doc.lines.take(from) + content + doc.lines.drop(to + 1)
        return doc.copy(lines = lines)
    }

    /** 在第 [line] 行之后插入；line = 总行数 ⇒ 追加到末尾，line = 0 ⇒ 插到最前面。 */
    private fun insertAfter(doc: Doc, patch: TextPatch.InsertAfter): Doc {
        val line = patch.line
        if (line < 0) {
            throw TextPatchException("行号不能为负：$line")
        }
        if (line > doc.lineCount) {
            throw TextPatchException(
                "行号越界：第 $line 行不存在，文件共 ${doc.lineCount} 行（可用 0..${doc.lineCount}，0 表示插到最前面）"
            )
        }
        val content = splitContent(patch.text)
        // line = 0 ⇒ take(0) 为空 ⇒ 插到最前面；line = 总行数 ⇒ drop(line) 为空 ⇒ 追加到末尾
        val lines = doc.lines.take(line) + content + doc.lines.drop(line)
        return doc.copy(lines = lines)
    }

    /** 删掉第 [line] 行。 */
    private fun deleteLine(doc: Doc, patch: TextPatch.DeleteLine): Doc {
        val line = patch.line
        if (line < 1) {
            throw TextPatchException("行号必须从 1 开始：第 $line 行")
        }
        if (line > doc.lineCount) {
            throw TextPatchException(
                "行号越界：第 $line 行不存在，文件共 ${doc.lineCount} 行"
            )
        }
        val lines = doc.lines.take(line - 1) + doc.lines.drop(line)
        return doc.copy(lines = lines)
    }

    // ---- 行内切分 / 锚点查找 ----

    /**
     * 把正文切成「行 + 行尾风格」。
     *
     * 行尾（CRLF/LF）单独记着：改完还得按原来的风格拼回去 ——
     * 补丁把 CRLF 文件写成 LF，diff 会满屏是改动，人也看不出到底改了哪儿。
     */
    private fun splitDoc(text: String): Doc {
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val normalized = if (eol == "\r\n") text.replace("\r\n", "\n") else text
        if (normalized.isEmpty()) return Doc(emptyList(), eol, trailingNewline = false)
        val trailingNewline = normalized.endsWith("\n")
        val raw = normalized.split("\n")
        // 末尾那个空串不是一行，是「文件以换行结尾」的标记 —— 单独记成标志位。
        val lines = if (trailingNewline) raw.dropLast(1) else raw
        return Doc(lines, eol, trailingNewline)
    }

    /** 补丁内容切成若干行：末尾的换行是「这一行的结束」，不算多出来的一行。 */
    private fun splitContent(text: String): List<String> {
        val parts = text.split("\n")
        return if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    /**
     * 锚点比较前的规范化：`\n`（转义写法）与真换行等价，真 CRLF 也折成 LF。
     *
     * agent 从规格里拼出来的锚点可能还带着转义写法，手工写代码构造的则是真换行 ——
     * 两种都得能命中同一段文本。
     */
    private fun normalizeAnchor(anchor: String): String =
        anchor.replace("\\n", "\n").replace("\r\n", "\n")

    /**
     * 找出所有「包含锚点的行块」。
     *
     * 做法：在按行拼起来的文本里找锚点出现的位置，再把字符位置映射回行号 ——
     * 这样多行锚点（锚点本身可以跨行）也能精确命中，
     * 而且「同一行里出现两次锚点」不会算成两处（改的是同一块）。
     *
     * @return 每个命中的行区间（0 基，含端点）。
     */
    private fun findAnchorHits(lines: List<String>, anchor: String): List<Pair<Int, Int>> {
        if (lines.isEmpty() || anchor.isEmpty()) return emptyList()
        val text = lines.joinToString("\n")
        val offsets = ArrayList<Int>(lines.size)
        var off = 0
        for (l in lines) {
            offsets.add(off)
            off += l.length + 1
        }
        val hits = LinkedHashSet<Pair<Int, Int>>()
        var from = 0
        while (true) {
            val idx = text.indexOf(anchor, from)
            if (idx < 0) break
            hits.add(lineIndexOf(offsets, idx) to lineIndexOf(offsets, idx + anchor.length - 1))
            from = idx + 1
        }
        return hits.toList()
    }

    /** 字符位置 → 行号（0 基）：找最后一个 offset <= pos 的行。 */
    private fun lineIndexOf(offsets: List<Int>, pos: Int): Int {
        var lo = 0
        var hi = offsets.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (offsets[mid] <= pos) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun lineRange(first: Int, last: Int): String = if (first == last) "$first" else "$first-$last"

    // ---- 内部结构 ----

    /** 一份按行切开的正文 + 它的行尾风格。 */
    private data class Doc(
        val lines: List<String>,
        val eol: String,
        /** 原文件是不是以换行结尾（删掉最后一行时不该凭空多出一个换行）。 */
        val trailingNewline: Boolean,
    ) {
        val lineCount: Int get() = lines.size

        /** 按原来的行尾风格拼回去。 */
        fun render(): String {
            val body = lines.joinToString(eol)
            return if (trailingNewline) body + eol else body
        }
    }

    /** 改动前后的正文一样时，描述就是这一句（agent 拿它确认「白改了」）。 */
    private const val NO_CHANGE = "无变化"

    /** `<数字> <数字> [内容]` —— 第三个组可有可无。 */
    private val TWO_NUM_ARGS = Regex("""^(\S+)\s+(\S+)(?:\s+(.*))?$""", RegexOption.DOT_MATCHES_ALL)

    /** `<数字> [内容]` —— 第二个组可有可无。 */
    private val ONE_NUM_ARGS = Regex("""^(\S+)(?:\s+(.*))?$""", RegexOption.DOT_MATCHES_ALL)
}