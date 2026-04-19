package com.siersi.sesagent.tools

import dev.langchain4j.agent.tool.ToolSpecification

/**
 * AI代理工具接口，定义工具的基本契约。
 * 所有具体工具都应实现此接口，以便被AI代理调用。
 */
internal interface AgentTool {
    
    /**
     * 工具规范，包含名称、描述和参数信息。
     * AI代理使用此信息决定如何调用工具。
     */
    val specification: ToolSpecification

    /**
     * 执行工具功能。
     * @param arguments 参数字符串（通常为JSON格式）
     * @return 执行结果字符串
     */
    fun execute(arguments: String): String
}