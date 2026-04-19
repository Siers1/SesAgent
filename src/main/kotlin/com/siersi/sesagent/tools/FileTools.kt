package com.siersi.sesagent.tools

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class ListFilesTool(
    private val access: FileAccessGuard
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("list_files")
        .description("列出目录下的文件和子目录，支持绝对路径或相对当前工作目录路径。")
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("path", "目录路径，留空表示当前工作目录。")
                .build()
        )
        .build()

    override fun execute(arguments: String): String = runCatching {
        val args = parseObject(arguments)
        val path = access.resolveDirectory(optionalString(args, "path"))
        val items = path.listDirectoryEntries()
            .sortedBy { it.fileName.toString() }
            .map {
                buildJsonObject {
                    put("name", it.name)
                    put("path", access.displayPath(it))
                    put("type", if (it.isDirectory()) "directory" else "file")
                }
            }
        success(
            buildJsonObject {
                put("path", access.displayPath(path))
                put("entries", JsonArray(items))
            }
        )
    }.getOrElse { error(it) }
}

internal class ReadFileTool(
    private val access: FileAccessGuard
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("read_file")
        .description("读取文件内容，可按行截取，支持绝对路径或相对当前工作目录路径。")
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("path", "文件路径。")
                .addIntegerProperty("startLine", "起始行号，从 1 开始，默认 1。")
                .addIntegerProperty("endLine", "结束行号，包含该行；留空表示读到文件末尾。")
                .required("path")
                .build()
        )
        .build()

    override fun execute(arguments: String): String = runCatching {
        val args = parseObject(arguments)
        val path = access.resolveFile(requiredString(args, "path"))
        val allLines = path.readLines()
        val startLine = optionalInt(args, "startLine")?.coerceAtLeast(1) ?: 1
        val endLine = optionalInt(args, "endLine")?.coerceAtMost(allLines.size) ?: allLines.size
        require(startLine <= endLine || allLines.isEmpty()) { "startLine 不能大于 endLine" }
        val content = if (allLines.isEmpty()) {
            ""
        } else {
            allLines.subList(startLine - 1, endLine).joinToString("\n")
        }

        success(
            buildJsonObject {
                put("path", access.displayPath(path))
                put("startLine", startLine)
                put("endLine", endLine)
                put("content", content)
            }
        )
    }.getOrElse { error(it) }
}

internal class WriteFileTool(
    private val access: FileAccessGuard
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("write_file")
        .description("写入文件内容；如果文件不存在则创建，存在则整体覆盖。")
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("path", "文件路径。")
                .addStringProperty("content", "要写入的完整文件内容。")
                .required("path", "content")
                .build()
        )
        .build()

    override fun execute(arguments: String): String = runCatching {
        val args = parseObject(arguments)
        val path = access.resolveForWrite(requiredString(args, "path"))
        val existed = path.exists()
        path.parent?.createDirectories()
        path.writeText(requiredString(args, "content"))
        success(
            buildJsonObject {
                put("path", access.displayPath(path))
                put("bytes", path.toFile().length())
                put("created", !existed)
            }
        )
    }.getOrElse { error(it) }
}

internal class AppendFileTool(
    private val access: FileAccessGuard
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("append_file")
        .description("向文件末尾追加内容；如果文件不存在则创建。")
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("path", "文件路径。")
                .addStringProperty("content", "要追加的文本内容。")
                .required("path", "content")
                .build()
        )
        .build()

    override fun execute(arguments: String): String = runCatching {
        val args = parseObject(arguments)
        val path = access.resolveForWrite(requiredString(args, "path"))
        val existed = path.exists()
        val previous = if (existed) path.readText() else ""
        path.parent?.createDirectories()
        path.writeText(previous + requiredString(args, "content"))
        success(
            buildJsonObject {
                put("path", access.displayPath(path))
                put("appended", true)
                put("created", !existed)
            }
        )
    }.getOrElse { error(it) }
}

internal class ReplaceInFileTool(
    private val access: FileAccessGuard
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("replace_in_file")
        .description("在文件中将指定文本替换为新的文本。")
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("path", "文件路径。")
                .addStringProperty("target", "待替换的原始文本，必须精确匹配。")
                .addStringProperty("replacement", "替换后的文本。")
                .required("path", "target", "replacement")
                .build()
        )
        .build()

    override fun execute(arguments: String): String = runCatching {
        val args = parseObject(arguments)
        val path = access.resolveFile(requiredString(args, "path"))
        access.ensureWriteAllowed(path)
        val target = requiredString(args, "target")
        val replacement = requiredString(args, "replacement")
        val original = path.readText()
        val occurrences = original.split(target).size - 1
        require(occurrences > 0) { "未找到要替换的文本。" }
        val updated = original.replace(target, replacement)
        path.writeText(updated)
        success(
            buildJsonObject {
                put("path", access.displayPath(path))
                put("replacements", occurrences)
            }
        )
    }.getOrElse { error(it) }
}

internal class ExecuteCommandTool(
    private val access: FileAccessGuard
) : AgentTool {
    override val specification: ToolSpecification = ToolSpecification.builder()
        .name("execute_command")
        .description("执行命令行指令，支持指定工作目录。返回 exitCode、stdout、stderr 和是否超时。")
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("command", "要执行的命令文本。")
                .addStringProperty("workingDirectory", "工作目录，支持绝对路径或相对当前工作目录路径；留空表示当前工作目录。")
                .addIntegerProperty("timeoutSec", "超时时间，单位秒，默认 60，最大 600。")
                .required("command")
                .build()
        )
        .build()

    override fun execute(arguments: String): String = runCatching {
        val args = parseObject(arguments)
        val command = requiredString(args, "command")
        val workingDirectory = access.resolveDirectory(optionalString(args, "workingDirectory"))
        val timeoutSec = (optionalInt(args, "timeoutSec") ?: 60).coerceIn(1, 600)
        val shellCommand = buildShellCommand(command)

        val process = ProcessBuilder(shellCommand)
            .directory(workingDirectory.toFile())
            .start()

        val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }

        val stdout = truncateOutput(process.inputStream.bufferedReader().readText())
        val stderr = truncateOutput(process.errorStream.bufferedReader().readText())
        val exitCode = if (finished) process.exitValue() else -1

        success(
            buildJsonObject {
                put("command", command)
                put("workingDirectory", access.displayPath(workingDirectory))
                put("timeoutSec", timeoutSec)
                put("timedOut", !finished)
                put("exitCode", exitCode)
                put("stdout", stdout)
                put("stderr", stderr)
            }
        )
    }.getOrElse { error(it) }

    private fun buildShellCommand(command: String): List<String> {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        return if (isWindows) {
            listOf("powershell", "-NoProfile", "-Command", command)
        } else {
            listOf("sh", "-lc", command)
        }
    }

    private fun truncateOutput(text: String, maxChars: Int = 12000): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n...[output truncated]..."
    }
}

internal class FileAccessGuard(
    private val cwd: Path
) {
    private val allowProtectedWrite =
        System.getenv("SESAGENT_ALLOW_PROTECTED_WRITE").orEmpty().equals("true", ignoreCase = true)
    private val protectedWriteRoots = protectedRoots()

    fun resolveFile(rawPath: String): Path {
        val path = resolvePath(rawPath)
        require(path.exists()) { "文件不存在: ${displayPath(path)}" }
        require(!path.isDirectory()) { "目标不是文件: ${displayPath(path)}" }
        return path
    }

    fun resolveDirectory(rawPath: String?): Path {
        val path = resolvePath(rawPath.orEmpty().ifBlank { "." })
        require(path.exists()) { "目录不存在: ${displayPath(path)}" }
        require(path.isDirectory()) { "目标不是目录: ${displayPath(path)}" }
        return path
    }

    fun resolveForWrite(rawPath: String): Path {
        val path = resolvePath(rawPath)
        ensureWriteAllowed(path)
        return path
    }

    fun ensureWriteAllowed(path: Path) {
        if (allowProtectedWrite) return
        require(path.parent != null) { "禁止直接写入磁盘根路径: ${displayPath(path)}" }
        val blockedBy = protectedWriteRoots.firstOrNull { path.startsWith(it) }
        require(blockedBy == null) {
            "出于安全考虑，禁止写入受保护路径: ${displayPath(path)}。如确需写入，请设置 SESAGENT_ALLOW_PROTECTED_WRITE=true。"
        }
    }

    fun displayPath(path: Path): String = path.pathString.replace('\\', '/')

    private fun resolvePath(rawPath: String): Path {
        val value = rawPath.trim()
        require(value.isNotBlank()) { "路径不能为空。" }
        val asGiven = Path.of(value)
        val resolved = if (asGiven.isAbsolute) asGiven else cwd.resolve(asGiven)
        return resolved.normalize().absolute()
    }

    private fun protectedRoots(): List<Path> {
        val roots = mutableListOf<Path>()
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) {
            listOfNotNull(
                System.getenv("SystemRoot"),
                System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)"),
                System.getenv("ProgramData")
            ).forEach { roots.add(Path.of(it).normalize().absolute()) }
        } else {
            listOf("/bin", "/sbin", "/usr", "/etc", "/var", "/boot", "/sys", "/proc", "/dev")
                .forEach { roots.add(Path.of(it).normalize().absolute()) }
        }
        return roots
    }
}

private fun parseObject(arguments: String): JsonObject =
    json.parseToJsonElement(arguments).jsonObject

private fun requiredString(args: JsonObject, key: String): String =
    args[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("缺少必填参数 `$key`。")

private fun optionalString(args: JsonObject, key: String): String? =
    args[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun optionalInt(args: JsonObject, key: String): Int? =
    args[key]?.jsonPrimitive?.intOrNull

private fun success(payload: JsonObject): String =
    json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("ok", true)
        put("data", payload)
    })

private fun error(throwable: Throwable): String =
    json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("ok", false)
        put("error", throwable.message ?: throwable.javaClass.simpleName)
    })

private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}
