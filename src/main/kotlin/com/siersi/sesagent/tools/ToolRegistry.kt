package com.siersi.sesagent.tools

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ToolExecutionResultMessage
import kotlin.io.path.Path

internal class ToolRegistry {
    private val guard = FileAccessGuard(Path("").toAbsolutePath().normalize())
    private val tools = listOf(
        ListFilesTool(guard),
        ReadFileTool(guard),
        WriteFileTool(guard),
        AppendFileTool(guard),
        ReplaceInFileTool(guard),
        ExecuteCommandTool(guard)
    )
    private val toolsByName = tools.associateBy { it.specification.name() }

    fun specifications(): List<ToolSpecification> =
        tools.map { it.specification }

    fun execute(request: ToolExecutionRequest): ToolExecutionResultMessage {
        val tool = toolsByName[request.name()]
        val result = tool?.execute(request.arguments()) ?: """{"ok":false,"error":"未知工具: ${request.name()}"}"""
        return ToolExecutionResultMessage.from(request, result)
    }
}
