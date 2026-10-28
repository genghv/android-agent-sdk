package ai.agentkernel.android

import ai.agentkernel.device.DeviceCapabilityRegistry
import ai.agentkernel.prefix.PrefixEnvironment
import ai.agentkernel.prefix.PrefixLayout
import ai.agentkernel.tools.exec.CommandExecutor
import ai.agentkernel.tools.exec.ExecResult
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * 无 PTY 的命令执行器 —— 对应 Termux 的 AppShell 路径（适合 agent 非交互执行）。
 *
 * 为什么不用 PTY：内核不需要交互终端。没有 PTY 反而更稳（不会因为没人读终端而阻塞）。
 * 若要跑需要完整用户态的二进制（如自带 node / busybox），把 [layout] 指向 App 私有目录下的
 * 「拟 Linux」根，并把 bin 加进 PATH 即可。
 *
 * 特权操作（装应用 / 点屏 / 改系统设置）不在这里做，交给 [ai.agentkernel.android.shizuku.ShizukuBridge]。
 *
 * ## 为什么 [layout] 是必填的
 *
 * 不能用写死的 "/data/data/ai.agentkernel/files"：
 * - 宿主的包名不是 `ai.agentkernel`，那条路径对宿主既不存在也不可访问；
 * - 多用户下真实路径是 `/data/user/<userId>/<pkg>/files`。
 *
 * 写错的代价是**静默**的：命令照样跑得起来（PATH 里还有 /system/bin），
 * 但 HOME / PREFIX / TMPDIR 指到别人的目录，`>` 重定向和临时文件直接失败。
 * 所以这里强制宿主用 [PrefixLayout] 明确给路径，最省事的写法是 [forContext]。
 *
 * ## 为什么读输出用的是「线程 + 有界 join」而不是协程
 *
 * 踩过的坑：子进程被杀掉后，父进程的管道读端**不一定收到 EOF** ——
 * - Windows 上父进程仍持有管道写端的副本，子进程被 TerminateProcess 后读端照样挂着；
 * - 子进程若派生了继承管道的孙进程，杀掉子进程同样收不到 EOF。
 *
 * 原来的写法（`async { inputStream.readText() }` 然后 `await()`）在这种情况下
 * **永不返回** —— 一条卡住的命令就能把 agent 永久挂死。
 * 现在读输出用独立守护线程 + **有界 join**：拿不到就返回已经拿到的，
 * 绝不无限等。
 */
class AndroidCommandExecutor(
    private val shellPath: String = DEFAULT_SHELL,
    private val workingDir: File? = null,
    /**
     * 拟 Linux 的目录布局。
     *
     * 公开出来是为了让宿主拿它生成**系统提示词**（路径是运行时才知道的事实，
     * 提示词里要写真实的 HOME / PREFIX / TMPDIR，模型才知道自己在哪儿）。
     */
    val layout: PrefixLayout,
    /**
     * 设备能力表（可选）。
     *
     * 填了之后，`termux-*` 这类能力命令**由宿主在进程内执行**，不经过 shell：
     * - 整条就是一个能力命令 → 直接执行，给出真实退出码；
     * - 复合命令里夹着能力调用 → 能力先跑，结果经环境变量交给 shell 内建的 `printf` 打印。
     *
     * 这样**完全绕开 noexec**（不需要把二进制塞进 nativeLibraryDir、不用拆 ABI），
     * 也不需要改 bootstrap 里的 shebang 与包名路径。
     *
     * 公开出来是为了让 [AndroidAgentSdk] 把同一份能力清单写进工具描述 ——
     * 模型看不见清单就不会用这些命令。
     */
    val capabilities: DeviceCapabilityRegistry? = null,
) : CommandExecutor {

    // 环境变量统一由 PrefixEnvironment 产出（对齐 TermuxShellEnvironment），
    // 再补上系统 bin，保证系统自带命令仍可用。
    //
    // PATH 的分隔符跟平台走（Android `:`，Windows `;`）——写死 `:` 的话，
    // 在非 Unix 平台上整条 PATH 会塌成一个不存在的目录名，
    // shell 内建命令还能用，但 cat / ls 这类外部程序一律 command not found。
    private val env: Map<String, String> = PrefixEnvironment.build(layout).toMutableMap().apply {
        val sep = File.pathSeparator
        this["PATH"] = "${this["PATH"]}$sep/system/bin$sep/system/xbin"
    }

    companion object {
        const val DEFAULT_SHELL = "/system/bin/sh"

        /** 正常收尾的等待上限：读端收不到 EOF 时，最多再等这么久就放弃。 */
        private const val DRAIN_TIMEOUT_MS = 2_000L

        /**
         * 杀进程之后的等待上限：比正常收尾短得多。
         *
         * 进程已经被杀了，它产生过的输出这时基本都读完了；剩下的多半是
         * 「读端永远等不到 EOF」那种坏情况，不值得为它让调用方多等几秒。
         */
        private const val DRAIN_AFTER_KILL_MS = 300L

        /**
         * 宿主最常见的入口：filesDir 由宿主的 [Context] 决定，绝不写死包名。
         *
         * ```
         * val sdk = AndroidAgentSdk(
         *     llm = ChatCompletionsClient(baseUrl, apiKey, OkHttpTransport()),
         *     model = "deepseek-chat",
         *     executor = AndroidCommandExecutor.forContext(context),
         * )
         * ```
         */
        fun forContext(context: Context, shellPath: String = DEFAULT_SHELL): AndroidCommandExecutor =
            AndroidCommandExecutor(
                shellPath = shellPath,
                layout = PrefixLayout(context.filesDir.path),
            )

        /**
         * 带设备能力的入口 —— 宿主只需要组装一次：
         * 能力表进执行器（命令能真的执行），同一份清单也会进工具描述（模型知道它存在）。
         *
         * ```
         * val caps = DeviceCapabilityRegistry(defaultDeviceCapabilities(context))
         * val sdk = AndroidAgentSdk(
         *     llm = ChatCompletionsClient(baseUrl, apiKey, OkHttpTransport()),
         *     model = "deepseek-chat",
         *     executor = AndroidCommandExecutor.forContext(context, caps),
         * )
         * ```
         */
        fun forContext(
            context: Context,
            capabilities: DeviceCapabilityRegistry,
            shellPath: String = DEFAULT_SHELL,
        ): AndroidCommandExecutor =
            AndroidCommandExecutor(
                shellPath = shellPath,
                layout = PrefixLayout(context.filesDir.path),
                capabilities = capabilities,
            )
    }

    override suspend fun execute(command: String, timeoutMs: Long): ExecResult = withContext(Dispatchers.IO) {
        val trimmed = command.trim()
        val caps = capabilities

        // ① 整条就是一个能力命令 → 进程内执行（真实退出码，不用起子进程）
        if (caps != null) {
            caps.parseWhole(trimmed)?.let { capabilityCommand ->
                val result = caps.execute(capabilityCommand)
                return@withContext ExecResult(
                    exitCode = result.exitCode,
                    stdout = result.output,
                    stderr = "",
                )
            }
        }

        // ② 复合命令里夹着能力调用 → 能力先跑，结果经**环境变量**交给 shell 打印。
        //    （最终执行的是 shell 内建的 printf，所以照样绕开 noexec，
        //      也不依赖宿主还能找到 cat / ls 这类外部程序）
        var effectiveCommand = trimmed
        var capabilityEnv: Map<String, String> = emptyMap()
        if (caps != null) {
            val plan = caps.plan(trimmed)
            if (plan != null) {
                val results = plan.calls.map { caps.execute(it.command) }
                val materialized = caps.materialize(plan, results)
                effectiveCommand = materialized.command
                capabilityEnv = materialized.env
            }
        }

        val pb = ProcessBuilder(shellPath, "-c", effectiveCommand)
        // 工作目录默认是 **HOME**（= `layout.home`），不是进程继承的 `/`。
        //
        // Termux 起的 shell 就在 `$HOME` 里；不设的话，在 Android 上 cwd 是 `/`，
        // 于是 `ls -la .` 会去列根目录 → `Permission denied`，
        // 而模型/用户想看的是「我的私有目录」。
        // HOME 已经写进环境变量了，cwd 跟它对齐才不矛盾。
        val dir = workingDir ?: File(layout.home)
        if (!dir.exists()) dir.mkdirs()
        if (dir.isDirectory) pb.directory(dir)

        // **TMPDIR / HOME 指向的目录必须真的存在。**
        //
        // `<filesDir>/usr/tmp` 只有在**装了 bootstrap** 之后才存在；
        // 没装 bootstrap 的宿主（或者只想要能力层的宿主）里它是空的。
        // 于是 shell 里的 heredoc（写多行文件）会因为「无法创建临时文件」而失败：
        // ```
        // /system/bin/sh: can't create temporary file /data/local/shXXXX.tmp: Permission denied
        // ```
        // （mksh 找不到可用的 TMPDIR 就回退到硬编码的 `/data/local`，那里 App 没权限。）
        // 所以这里负责把这两个目录建出来 —— **环境变量指向哪儿，谁就得负责把它建出来**。
        File(layout.tmp).mkdirs()
        File(layout.home).mkdirs()

        pb.environment().putAll(env)
        pb.environment().putAll(capabilityEnv)
        pb.redirectErrorStream(false)

        val proc = pb.start()

        // stdout / stderr 各用一个守护线程读：既避免任一缓冲区写满导致子进程卡死，
        // 也避免「读端等不到 EOF」时把这里永久挂住。
        val out = StreamCollector(proc.inputStream, "agentkernel-stdout")
        val err = StreamCollector(proc.errorStream, "agentkernel-stderr")
        out.start()
        err.start()

        // Process.waitFor(timeout, unit) 与 destroyForcibly() 都是 **API 26** 才有的，
        // 而本模块声明 minSdk 24 —— 直接调用会在 Android 7.0/7.1 上抛 NoSuchMethodError。
        // 改用「独立等待线程 + Thread.join(timeout)」实现超时：Thread.join 是 API 1，
        // 行为在所有版本一致；强杀再按 SDK_INT 分流。
        //
        // 注意：Java 的 Thread.join(long) 返回 **void**，判断是否超时要看线程还活着没有。
        val waiter = Thread({ proc.waitFor() }, "agentkernel-exec-waiter").apply {
            isDaemon = true
        }
        waiter.start()
        waiter.join(timeoutMs)
        val finished = !waiter.isAlive

        if (!finished) {
            killProcess(proc)
        }

        // 有界收尾：子进程可能派生孙进程，读端未必收到 EOF —— 只等有限时间。
        val drainMs = if (finished) DRAIN_TIMEOUT_MS else DRAIN_AFTER_KILL_MS
        out.join(drainMs)
        err.join(drainMs)

        if (!finished) {
            ExecResult(
                exitCode = -1,
                stdout = out.text(),
                stderr = err.text(),
                timedOut = true,
            )
        } else {
            ExecResult(proc.exitValue(), out.text(), err.text())
        }
    }

    /** 结束超时未退的子进程：API 26+ 用强杀，更早的版本退回 SIGTERM。 */
    private fun killProcess(proc: Process) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            proc.destroyForcibly()
        } else {
            proc.destroy()
        }
    }

    /**
     * 把一个流读完并缓存下来。
     *
     * 用普通线程而不是协程：阻塞读是不可取消的，协程一旦卡在 read 上，
     * 连 `withTimeout` 都救不回来（只能取消上层作用域，而流本身还在读）。
     */
    private class StreamCollector(private val stream: InputStream, threadName: String) {
        private val bytes = ByteArrayOutputStream()
        private val thread = Thread({
            try {
                val buf = ByteArray(8192)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    synchronized(bytes) { bytes.write(buf, 0, n) }
                }
            } catch (_: Exception) {
                // 流被关闭 / 进程被杀：到这里就够了，已读到的内容照样交付。
            }
        }, threadName).apply { isDaemon = true }

        fun start() = thread.start()

        fun join(timeoutMs: Long) {
            try {
                thread.join(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        fun text(): String = synchronized(bytes) { bytes.toByteArray().toString(Charsets.UTF_8) }
    }
}
