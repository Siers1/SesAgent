# SesAgent

SesAgent 是一个运行在命令行中的代码开发助手。

它基于 Kotlin 和 LangChain4j 实现，提供一个轻量 TUI 界面，支持流式回复、工具调用、文件读写和命令执行，适合在本地直接协助代码阅读、修改、调试和排查问题。

## Features

- 命令行 TUI 交互
- 流式模型输出
- 多轮会话上下文
- 文件系统工具
- 命令执行工具
- 自定义模型配置
- 安装到系统 `PATH` 后可直接通过 `sesagent` 启动

## Requirements

- JDK 21
- Windows PowerShell
- 可用的 OpenAI 兼容接口

## Build

在项目根目录执行：

```powershell
.\gradlew.bat shadowJar
```

构建产物：

```text
build\libs\sesagent.jar
```

## Install

执行安装脚本：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install-sesagent.ps1 -Rebuild
```

安装脚本会完成以下工作：

- 构建最新 `shadowJar`
- 复制 `sesagent.jar` 到 `C:\Users\<用户名>\.sesagent\bin`
- 生成 `sesagent.cmd`
- 将安装目录加入当前用户的 `PATH`

安装完成后，重新打开一个终端窗口并执行：

```powershell
sesagent
```

## Configuration

配置文件位于：

```text
%USERPROFILE%\.sesagent\config.json
```

可以在 TUI 中使用：

```text
/model edit
/model load
```

配置项示例：

```json
{
  "apiKey": "",
  "baseUrl": "https://api.openai.com/v1",
  "model": "gpt-4o-mini",
  "temperature": 0.2,
  "timeoutSec": 90,
  "systemPrompt": "你是 SesAgent，一个擅长协助代码开发的小助手。请以中文回答，保持简洁、准确、务实，优先帮助用户解决实际工程问题。"
}
```

## Usage

启动：

```powershell
sesagent
```

TUI 内置命令：

```text
/help
/exit
/quit
/clear
/model show
/model edit
/model load
```

## Built-in Tools

当前内置工具如下：

- `list_files`
  - 列出目录下的文件和子目录
- `read_file`
  - 按文件或按行读取内容
- `write_file`
  - 覆盖写入文件
- `append_file`
  - 向文件末尾追加内容
- `replace_in_file`
  - 按精确文本替换文件内容
- `execute_command`
  - 执行命令行指令，返回 `exitCode`、`stdout`、`stderr`、`timedOut`

## Safety

SesAgent 当前有以下保护：

- 不允许写入系统受保护路径
- 如确需写入受保护路径，可设置：

```powershell
$env:SESAGENT_ALLOW_PROTECTED_WRITE = "true"
```

命令执行工具当前会直接调用系统 shell：

- Windows: `powershell -NoProfile -Command`
- Unix-like: `sh -lc`

这意味着它具备真实执行能力，使用时应明确约束模型行为。

## Implementation Notes

- 使用 `OpenAiStreamingChatModel` 提供流式输出
- 工具调用通过 LangChain4j tool calling 完成
- 会话历史保留最近 24 条消息
- 单轮工具调用最多 90 轮

## Known Limitations

- 当前 TUI 是轻量实现，不是完整终端 UI 框架
- 工具状态展示和流式输出仍偏控制台风格
- `execute_command` 目前没有做高风险命令黑名单
- Windows 终端兼容性依赖实际 ANSI 支持情况

## Development

编译检查：

```powershell
.\gradlew.bat compileKotlin
```

重新安装到系统：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install-sesagent.ps1 -Rebuild
```
