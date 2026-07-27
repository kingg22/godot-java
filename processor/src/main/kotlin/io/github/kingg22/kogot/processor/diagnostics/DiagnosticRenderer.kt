package io.github.kingg22.kogot.processor.diagnostics

/**
 * Renders diagnostics to various output formats.
 */
object DiagnosticRenderer {
    /**
     * Renders a diagnostic in rustc-style format.
     */
    fun renderRustc(message: DiagnosticMessage): String {
        val sb = StringBuilder()
        val severityStr = when (message.severity) {
            ERROR -> "error"
            WARNING -> "warning"
            INFO -> "info"
        }

        sb.appendLine("$severityStr[${message.code}]: ${message.mainMessage}")
        sb.appendLine("  --> ${message.location.filePath}:${message.location.line}:${message.location.column}")

        if (message.contextLines.isNotEmpty()) {
            sb.appendLine("   |")
            message.contextLines.forEachIndexed { index, line ->
                val lineNum = message.location.line - message.contextLines.size + index
                sb.appendLine(" $lineNum | $line")
            }
            sb.appendLine("   |")
        }

        message.help?.let { help ->
            sb.appendLine("   = help: $help")
        }
        message.note?.let { note ->
            sb.appendLine("   = note: $note")
        }

        return sb.toString()
    }
}
