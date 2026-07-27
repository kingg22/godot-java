package io.github.kingg22.kogot.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import io.github.kingg22.kogot.processor.diagnostics.DiagnosticCode
import io.github.kingg22.kogot.processor.diagnostics.DiagnosticLocation
import io.github.kingg22.kogot.processor.diagnostics.DiagnosticMessage
import io.github.kingg22.kogot.processor.diagnostics.DiagnosticRenderer
import io.github.kingg22.kogot.processor.generators.kotlin.GodotBindingGenerator
import io.github.kingg22.kogot.processor.ksp.toClassInfo
import io.github.kingg22.kogot.processor.model.ClassInfo
import io.github.kingg22.kogot.processor.model.GodotPrimitives
import io.github.kingg22.kogot.processor.model.TypeInfo
import io.github.kingg22.kogot.processor.model.getExportedProperties
import io.github.kingg22.kogot.processor.model.isGodotBuiltin

/**
 * KSP SymbolProcessor for Kogot bindings.
 */
class KogotProcessor(environment: SymbolProcessorEnvironment) : SymbolProcessor {

    private val codeGenerator: CodeGenerator = environment.codeGenerator
    private val logger: KSPLogger = environment.logger
    private val generator = GodotBindingGenerator()

    override fun process(resolver: Resolver): List<KSClassDeclaration> {
        logger.info("KogotProcessor: Starting processing")

        val classes = extractClasses(resolver)
        logger.info("KogotProcessor: Found ${classes.size} classes with @Godot annotation")

        if (classes.isEmpty()) {
            logger.info("KogotProcessor: No classes found, returning emptyList")
            return emptyList()
        }

        val classInfos = classes.map { it.toClassInfo() }
        val containingFileByQualifiedName = classes.zip(classInfos)
            .mapNotNull { (ksClass, info) -> ksClass.containingFile?.let { info.qualifiedName to it } }
            .toMap()

        // Validate
        val validationDiagnostics = validateClasses(classInfos)
        for (diagnostic in validationDiagnostics) {
            when (diagnostic.severity) {
                ERROR -> logger.error(DiagnosticRenderer.renderRustc(diagnostic))
                WARNING -> logger.warn(DiagnosticRenderer.renderRustc(diagnostic))
                INFO -> logger.info(DiagnosticRenderer.renderRustc(diagnostic))
            }
        }
        if (validationDiagnostics.any { it.isError() }) return emptyList()

        // Generate
        val result = generator.generate(classInfos)
        for (diagnostic in result.diagnostics) {
            when (diagnostic.severity) {
                ERROR -> logger.error(DiagnosticRenderer.renderRustc(diagnostic))
                WARNING -> logger.warn(DiagnosticRenderer.renderRustc(diagnostic))
                INFO -> logger.info(DiagnosticRenderer.renderRustc(diagnostic))
            }
        }

        for ((relativePath, content, sourceClassNames) in result.files) {
            writeGeneratedFile(relativePath, content, sourceClassNames, containingFileByQualifiedName)
        }

        return emptyList()
    }

    private fun extractClasses(resolver: Resolver): Set<KSClassDeclaration> {
        // Find all classes with @Godot annotation
        val godotAnnotation = "io.github.kingg22.godot.api.annotations.Godot"

        return resolver.getSymbolsWithAnnotation(godotAnnotation)
            .filterIsInstance<KSClassDeclaration>()
            .toSet()
    }

    private fun validateClasses(classInfos: List<ClassInfo>): List<DiagnosticMessage> {
        val diagnostics = mutableListOf<DiagnosticMessage>()

        for (classInfo in classInfos) {
            for ((_, type) in classInfo.getExportedProperties()) {
                if (!isValidExportType(type)) {
                    diagnostics.add(
                        DiagnosticMessage.error(
                            code = DiagnosticCode.INVALID_EXPORT_TYPE,
                            message = "@Export on unsupported type '${type.qualifiedName}'",
                            location = DiagnosticLocation(
                                classInfo.filePath,
                                classInfo.lineNumber,
                                0,
                            ),
                            help = "Supported types: primitives (Int, Float, String, etc.) and Godot builtin types",
                            note = "This property will not appear in the Inspector",
                        ),
                    )
                }
            }
        }

        return diagnostics
    }

    private fun isValidExportType(type: TypeInfo): Boolean =
        GodotPrimitives.isPrimitive(type.qualifiedName) || type.isGodotBuiltin()

    private fun writeGeneratedFile(
        relativePath: String,
        content: String,
        sourceClassNames: List<String>,
        containingFileByQualifiedName: Map<String, KSFile>,
    ) {
        val parts = relativePath.split("/")
        val packageName = if (parts.size > 1) parts.dropLast(1).joinToString(".") else ""
        // Remove .kt extension if present since extensionName will add it
        val fileNameWithExt = parts.last()
        val fileName = fileNameWithExt.removeSuffix(".kt")
        check(fileName.isNotBlank()) { "File name is empty" }

        logger.info("Generating file: $relativePath with ${content.length} chars")

        val sourceFiles = sourceClassNames.mapNotNull { containingFileByQualifiedName[it] }.distinct()
        val dependencies = if (sourceFiles.isEmpty()) {
            Dependencies.ALL_FILES
        } else {
            Dependencies(false, *sourceFiles.toTypedArray())
        }

        codeGenerator.createNewFile(
            packageName = packageName,
            fileName = fileName,
            extensionName = "kt",
            dependencies = dependencies,
        ).writer().use { it.write(content) }
    }

    class Provider : SymbolProcessorProvider {
        override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = KogotProcessor(environment)
    }
}
