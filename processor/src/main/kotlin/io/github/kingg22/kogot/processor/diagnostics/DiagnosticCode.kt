package io.github.kingg22.kogot.processor.diagnostics

/**
 * Unique identifier for a diagnostic (error/warning).
 * Format: KOGOT-XXX (e.g., KOGOT-001, KOGOT-002)
 */
data class DiagnosticCode(val code: String) {
    companion object {
        val INVALID_EXPORT_TYPE = DiagnosticCode("KOGOT-101")
        val GENERATION_FAILED = DiagnosticCode("KOGOT-201")
    }

    override fun toString(): String = code
}
