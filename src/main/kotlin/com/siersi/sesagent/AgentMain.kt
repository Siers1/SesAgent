package com.siersi.sesagent

import com.siersi.sesagent.tools.ToolRegistry
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.StreamingResponseHandler
import dev.langchain4j.model.chat.StreamingChatLanguageModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.model.output.Response
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
    SesChatApp().start()
    exitProcess(0)
}

private class SesChatApp {
    private val configStore = ModelConfigStore()
    private val session = ChatSession()
    private val toolRegistry = ToolRegistry()
    private val tui = ChatTui()

    fun start() {
        val initialState = loadState(configStore.load())
        runTui(initialState)
    }

    private fun runTui(initialState: RuntimeState) {
        var state = initialState
        tui.enter()
        try {
            tui.addHeader(
                listOf(
                    "SesAgent TUI",
                    "输入文本发送，命令以 `/` 开头，`/help` 查看命令，`/exit` 退出。",
                    "工作目录: ${Path("").toAbsolutePath().normalize()}",
                    "配置文件: ${Path(System.getProperty("user.home"), ".sesagent", "config.json")}"
                )
            )
            state.warnings.forEach { tui.addLine("warning > $it") }

            while (true) {
                tui.render(statusLine(state))
                val input = readlnOrNull()?.removePrefix("\uFEFF")?.trim() ?: break
                if (input.isBlank()) continue

                if (input.startsWith("/")) {
                    tui.addLine("you > $input")
                    val outcome = handleCommand(input, state)
                    state = outcome.state
                    outcome.messages.forEach { tui.addLine(it) }
                    if (outcome.exit) {
                        tui.close()
                        exitProcess(0)
                    }
                    continue
                }

                tui.addLine("you > $input")
                if (state.model == null) {
                    tui.addLine("assistant > 当前未配置可用模型，请执行 `/model edit` 后 `/model load`。")
                    continue
                }

                try {
                    tui.beginAssistantResponse()
                    val answer = runAgentTurn(
                        model = state.model,
                        config = state.config,
                        userInput = input,
                        onToken = { token -> tui.appendAssistantToken(token) },
                        onToolStatusChanged = { status -> tui.setToolStatus(status) }
                    )
                    tui.finishAssistantResponse(answer)
                } catch (e: Exception) {
                    tui.abortAssistantResponse()
                    tui.addLine("assistant > 请求失败: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    tui.setToolStatus(null)
                }
            }
        } finally {
            tui.close()
        }
    }

    private fun handleCommand(raw: String, current: RuntimeState): CommandOutcome {
        val trimmed = raw.trim()
        return when {
            trimmed == "/help" -> CommandOutcome(
                state = current,
                messages = helpLines()
            )

            trimmed == "/exit" || trimmed == "/quit" -> CommandOutcome(
                exit = true,
                state = current
            )

            trimmed == "/clear" -> {
                session.clear()
                CommandOutcome(
                    state = current,
                    messages = listOf("system > 已清空当前会话上下文。")
                )
            }

            trimmed == "/model show" -> CommandOutcome(
                state = current,
                messages = modelConfigLines(current.config)
            )

            trimmed == "/model edit" -> {
                try {
                    configStore.openInEditor()
                    CommandOutcome(
                        state = current,
                        messages = listOf("system > 已打开配置文件，请编辑后执行 `/model load`。")
                    )
                } catch (e: Exception) {
                    CommandOutcome(
                        state = current,
                        messages = listOf("error > 打开配置文件失败: ${e.message}")
                    )
                }
            }

            trimmed == "/model load" -> {
                try {
                    val loaded = configStore.loadOrThrow()
                    val newState = loadState(loaded)
                    if (newState.model == null) {
                        CommandOutcome(
                            state = current,
                            messages = listOf("error > 模型加载失败: ${newState.warnings.joinToString("；")}")
                        )
                    } else {
                        CommandOutcome(
                            state = newState.copy(warnings = emptyList()),
                            messages = listOf(
                                "system > 模型配置已重新加载: ${configStore.filePath()}",
                                "system > 当前模型: ${loaded.model}"
                            )
                        )
                    }
                } catch (e: Exception) {
                    CommandOutcome(
                        state = current,
                        messages = listOf("error > 模型加载失败: ${e.message ?: e.javaClass.simpleName}")
                    )
                }
            }

            else -> CommandOutcome(
                state = current,
                messages = listOf("error > 未知命令: $trimmed，可用 `/help` 查看帮助。")
            )
        }
    }

    private fun statusLine(state: RuntimeState): String {
        val modelLabel = state.config.model.ifBlank { "(未配置)" }
        val modelState = if (state.model == null) "未就绪" else "就绪"
        return "model=$modelLabel | 状态=$modelState | 历史=${session.size()}/${MAX_MESSAGES_IN_CONTEXT}"
    }

    private fun helpLines(): List<String> = listOf(
        "可用命令:",
        "/help                查看帮助",
        "/exit 或 /quit       退出程序",
        "/clear               清空会话上下文",
        "/model show          查看当前模型配置",
        "/model edit          用系统文本编辑器打开配置文件",
        "/model load          重新加载配置并执行模型参数校验"
    ).map { "system > $it" }

    private fun modelConfigLines(config: ModelConfig): List<String> {
        val key = if (config.apiKey.isBlank()) "(未设置，可能使用 OPENAI_API_KEY)" else maskKey(config.apiKey)
        return listOf(
            "system > 当前模型配置:",
            "system > - apiKey: $key",
            "system > - baseUrl: ${config.baseUrl.ifBlank { "(默认 OpenAI)" }}",
            "system > - model: ${config.model.ifBlank { "(未配置)" }}",
            "system > - temperature: ${config.temperature}",
            "system > - timeoutSec: ${config.timeoutSec}",
            "system > - systemPrompt: ${config.systemPrompt}"
        )
    }

    private fun loadState(config: ModelConfig): RuntimeState {
        val validation = validateConfig(config)
        if (validation.errors.isNotEmpty()) {
            return RuntimeState(config = config, model = null, warnings = validation.errors)
        }

        val model = createChatModel(config, validation.baseUrl)
        return RuntimeState(config = config, model = model, warnings = emptyList())
    }

    private fun runAgentTurn(
        model: StreamingChatLanguageModel,
        config: ModelConfig,
        userInput: String,
        onToken: (String) -> Unit,
        onToolStatusChanged: (String?) -> Unit
    ): String {
        val messages = session.beginTurn(systemPrompt(config), userInput)

        repeat(MAX_TOOL_ROUNDS) {
            val response = generateStreamingResponse(
                model = model,
                messages = messages,
                onToken = onToken
            )
            val aiMessage = response.content()
            messages += aiMessage

            if (!aiMessage.hasToolExecutionRequests()) {
                session.commit(messages)
                return aiMessage.text()?.trim().orEmpty()
            }

            for (request in aiMessage.toolExecutionRequests()) {
                onToolStatusChanged(request.name())
                try {
                    val result = toolRegistry.execute(request)
                    messages += result
                } finally {
                    onToolStatusChanged(null)
                }
            }
        }

        throw IllegalStateException("工具调用轮数超限，已中止本次请求。")
    }

    private fun generateStreamingResponse(
        model: StreamingChatLanguageModel,
        messages: List<ChatMessage>,
        onToken: (String) -> Unit
    ): Response<dev.langchain4j.data.message.AiMessage> {
        val future = CompletableFuture<Response<dev.langchain4j.data.message.AiMessage>>()
        model.generate(messages, toolRegistry.specifications(), object : StreamingResponseHandler<dev.langchain4j.data.message.AiMessage> {
            override fun onNext(token: String) {
                onToken(token)
            }

            override fun onComplete(response: Response<dev.langchain4j.data.message.AiMessage>) {
                future.complete(response)
            }

            override fun onError(error: Throwable) {
                future.completeExceptionally(error)
            }
        })
        return future.get()
    }

    private fun systemPrompt(config: ModelConfig): String {
        return """
            你是 SesAgent，一个运行在命令行中的代码开发助手。
            回答语言: 中文。
            当前工作目录: ${Path("").toAbsolutePath().normalize()}

            角色定位:
            - 擅长协助用户进行代码阅读、实现、调试、重构与问题排查。
            - 优先提供直接、可靠、可执行的工程帮助，而不是空泛建议。
            - 不要把自己限定为某一种语言或技术栈的助手，应根据当前项目和任务选择合适方式。

            行为要求:
            - 当任务涉及查看、创建、修改文件时，优先使用可用工具，不要臆造文件内容。
            - 在真正修改文件前，先阅读相关文件，确保理解上下文。
            - 不限制工作区目录，但禁止写入系统受保护路径。
            - 工具执行失败时，结合错误信息调整后重试，或向用户说明阻塞点。
            - 最终回答要简洁，直接说明做了什么、改了什么、还有什么限制。

            用户给你的附加系统要求:
            ${config.systemPrompt}
        """.trimIndent()
    }
}

private data class RuntimeState(
    val config: ModelConfig,
    val model: StreamingChatLanguageModel?,
    val warnings: List<String>
)

private data class CommandOutcome(
    val exit: Boolean = false,
    val state: RuntimeState,
    val messages: List<String> = emptyList()
)

private class ChatTui {
    private val header = mutableListOf<String>()
    private val lines = mutableListOf<String>()
    private val supportsAnsi = supportsAnsi()
    private val outputLock = Any()
    private var nonAnsiInitialized = false
    private var nonAnsiPrintedLineCount = 0
    private var lastStatus = ""
    private var streamingActive = false
    @Volatile
    private var toolAnimationRunning = false
    private var toolAnimationThread: Thread? = null
    private var activeToolLine: String? = null
    private var toolLineRenderWidth = 0

    fun enter() {
        if (!supportsAnsi) return
        synchronized(outputLock) {
            print("\u001B[2J\u001B[H")
            System.out.flush()
        }
    }

    fun close() {
        stopToolAnimation()
    }

    fun addHeader(values: List<String>) {
        header.clear()
        header += values
    }

    fun addLine(line: String) {
        lines += line
    }

    fun setToolStatus(status: String?) {
        if (status.isNullOrBlank()) {
            stopToolAnimation()
        } else {
            startToolAnimation(status)
        }
    }

    fun beginAssistantResponse() {
        synchronized(outputLock) {
            print("assistant > ")
            System.out.flush()
        }
        streamingActive = true
    }

    fun appendAssistantToken(token: String) {
        if (token.isEmpty()) return
        synchronized(outputLock) {
            print(token)
            System.out.flush()
        }
    }

    fun finishAssistantResponse(finalText: String) {
        if (streamingActive) {
            synchronized(outputLock) {
                println()
                System.out.flush()
            }
            streamingActive = false
            rememberPrintedLine("assistant > $finalText")
            return
        }
        addLine("assistant > $finalText")
    }

    fun abortAssistantResponse() {
        if (streamingActive) {
            synchronized(outputLock) {
                println()
                System.out.flush()
            }
            streamingActive = false
        }
    }

    fun render(status: String) {
        lastStatus = status
        if (supportsAnsi) {
            synchronized(outputLock) {
                print("\u001B[2J\u001B[H")
                header.forEach(::println)
                println(status)
                println("-".repeat(88))
                val maxBody = (terminalRows() - header.size - 5).coerceAtLeast(12)
                lines.takeLast(maxBody).forEach(::println)
                println()
                print("you > ")
                System.out.flush()
            }
            return
        }

        synchronized(outputLock) {
            if (!nonAnsiInitialized) {
                header.forEach(::println)
                println(status)
                println("-".repeat(88))
                lines.forEach(::println)
                nonAnsiPrintedLineCount = lines.size
                nonAnsiInitialized = true
            } else {
                lines.drop(nonAnsiPrintedLineCount).forEach(::println)
                nonAnsiPrintedLineCount = lines.size
            }
            print("you > ")
            System.out.flush()
        }
    }

    private fun startToolAnimation(status: String) {
        stopToolAnimation()
        activeToolLine = "tools > $status"
        toolLineRenderWidth = 0
        synchronized(outputLock) {
            println()
            System.out.flush()
        }
        toolAnimationRunning = true
        toolAnimationThread = Thread {
            var dots = 0
            while (toolAnimationRunning) {
                val suffix = ".".repeat(dots + 1)
                writeToolStatusFrame("${activeToolLine.orEmpty()}$suffix")
                dots = (dots + 1) % 3
                try {
                    Thread.sleep(350)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "sesagent-tool-status"
            start()
        }
    }

    private fun stopToolAnimation() {
        toolAnimationRunning = false
        toolAnimationThread?.interrupt()
        toolAnimationThread?.join(100)
        toolAnimationThread = null
        finalizeToolStatusFrame()
    }

    private fun writeToolStatusFrame(text: String) {
        synchronized(outputLock) {
            val padded = text.padEnd(toolLineRenderWidth.coerceAtLeast(text.length), ' ')
            print("\r$padded")
            toolLineRenderWidth = maxOf(toolLineRenderWidth, text.length)
            System.out.flush()
        }
    }

    private fun finalizeToolStatusFrame() {
        synchronized(outputLock) {
            val finalLine = activeToolLine
            if (!finalLine.isNullOrBlank()) {
                val padded = finalLine.padEnd(toolLineRenderWidth.coerceAtLeast(finalLine.length), ' ')
                print("\r$padded")
                println()
                rememberPrintedLine(finalLine)
                System.out.flush()
            }
            activeToolLine = null
            toolLineRenderWidth = 0
        }
    }

    private fun rememberPrintedLine(line: String) {
        lines += line
        if (nonAnsiInitialized) {
            nonAnsiPrintedLineCount = lines.size
        }
    }

    private fun terminalRows(): Int =
        System.getenv("LINES")?.toIntOrNull()?.coerceAtLeast(20) ?: 36

    private fun supportsAnsi(): Boolean {
        val os = System.getProperty("os.name").lowercase()
        val term = System.getenv("TERM").orEmpty().lowercase()
        val wt = System.getenv("WT_SESSION")
        val ansicon = System.getenv("ANSICON")
        val conEmuAnsi = System.getenv("ConEmuANSI").orEmpty().lowercase()

        if (os.contains("win")) {
            return !wt.isNullOrBlank() || !ansicon.isNullOrBlank() || conEmuAnsi == "on"
        }
        return term.isNotBlank() && term != "dumb"
    }
}

private class ChatSession {
    private val history = mutableListOf<ChatMessage>()

    fun beginTurn(systemPrompt: String, userInput: String): MutableList<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        messages += SystemMessage.from(systemPrompt)
        messages += history.takeLast(MAX_MESSAGES_IN_CONTEXT)
        messages += UserMessage.from(userInput)
        return messages
    }

    fun commit(messages: List<ChatMessage>) {
        history.clear()
        history += messages
            .drop(1)
            .filter { it !is dev.langchain4j.data.message.ToolExecutionResultMessage }
            .takeLast(MAX_MESSAGES_IN_CONTEXT)
    }

    fun clear() {
        history.clear()
    }

    fun size(): Int = history.size
}

private class ModelConfigStore {
    private val configDir = Path(System.getProperty("user.home"), ".sesagent")
    private val configFile = configDir.resolve("config.json")

    fun load(): ModelConfig {
        return try {
            loadOrThrow()
        } catch (_: Exception) {
            ModelConfig()
        }
    }

    fun loadOrThrow(): ModelConfig {
        ensureFileExists()
        return json.decodeFromString(configFile.readText())
    }

    fun openInEditor() {
        ensureFileExists()
        if (!Desktop.isDesktopSupported()) {
            throw IllegalStateException("当前系统不支持桌面关联程序打开文件。")
        }
        Desktop.getDesktop().open(configFile.toFile())
    }

    fun filePath(): String = configFile.toString()

    private fun ensureFileExists() {
        configDir.createDirectories()
        if (!configFile.exists()) {
            configFile.writeText(json.encodeToString(ModelConfig()))
        }
    }
}

private data class ConfigValidation(
    val baseUrl: String?,
    val errors: List<String>
)

private fun validateConfig(config: ModelConfig): ConfigValidation {
    val errors = mutableListOf<String>()
    val key = config.apiKey.ifBlank { System.getenv("OPENAI_API_KEY").orEmpty().trim() }
    if (key.isBlank()) {
        errors += "未配置 apiKey（且环境变量 OPENAI_API_KEY 为空）。"
    }
    if (config.model.isBlank()) {
        errors += "model 不能为空。"
    }
    if (config.timeoutSec <= 0) {
        errors += "timeoutSec 必须大于 0。"
    }
    if (config.temperature !in 0.0..2.0) {
        errors += "temperature 必须在 [0, 2] 区间。"
    }

    val baseUrl = config.baseUrl.trim().trim('`', '"', '\'').ifBlank { null }
    if (baseUrl != null) {
        val normalized = baseUrl.trimEnd('/')
        if (normalized.endsWith("/chat/completions")) {
            errors += "baseUrl 不应包含 `/chat/completions`，应填写 API 根地址（例如 `https://api.openai.com/v1`）。"
        }
        if (!(normalized.startsWith("http://") || normalized.startsWith("https://"))) {
            errors += "baseUrl 必须以 http:// 或 https:// 开头。"
        }
    }
    return ConfigValidation(baseUrl = baseUrl, errors = errors)
}

private fun createChatModel(config: ModelConfig, baseUrl: String?): StreamingChatLanguageModel {
    val key = config.apiKey.ifBlank { System.getenv("OPENAI_API_KEY").orEmpty().trim() }
    return OpenAiStreamingChatModel.builder()
        .apiKey(key)
        .baseUrl(baseUrl)
        .modelName(config.model)
        .temperature(config.temperature)
        .timeout(Duration.ofSeconds(config.timeoutSec))
        .build()
}

private fun maskKey(key: String): String {
    if (key.length <= 8) return "****"
    return "${key.take(4)}****${key.takeLast(4)}"
}

@Serializable
private data class ModelConfig(
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val temperature: Double = 0.2,
    val timeoutSec: Long = 90,
    val systemPrompt: String = "你是 SesAgent，一个擅长协助代码开发的小助手。请以中文回答，保持简洁、准确、务实，优先帮助用户解决实际工程问题。"
)

private val json = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private const val MAX_MESSAGES_IN_CONTEXT = 24
private const val MAX_TOOL_ROUNDS = 90
