package me.sorihiko.dango.modern

import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import org.junit.Assert.*
import org.junit.Test

class JitWorkaroundTest {
    @Test
    fun nativeJvmLoadsBothRulesFromChineseWorkingDirectoryWithSpaces() {
        assertTrue(File(".").canonicalPath.contains("中文 空格"))
        assertTrue(System.getProperty("java.io.tmpdir").contains("临时目录 with spaces"))
        val before = diagnosticCommand("compilerDirectivesPrint")
        var installed = false
        try {
            JitWorkaround.installDirectives()
            installed = true
            val directives = diagnosticCommand("compilerDirectivesPrint")
            assertTrue(directives, directives.contains("me/sorihiko/dango/*.*"))
            assertTrue(directives, Regex("""Exclude:\s*true""").containsMatchIn(directives))
            assertTrue(directives, directives.contains("-me/sorihiko/dango/*.*"))
        } finally {
            if (installed) {
                repeat(2) { diagnosticCommand("compilerDirectivesRemove") }
                assertEquals(before, diagnosticCommand("compilerDirectivesPrint"))
            }
        }
    }

    @Test
    fun passesOnlyAsciiFileNameAndDeletesFileAfterSuccess() {
        var directiveFile: File? = null
        JitWorkaround.installDirectives { fileName ->
            assertTrue(fileName, Regex("[A-Za-z0-9.-]+").matches(fileName))
            val file = File(fileName)
            directiveFile = file
            assertTrue(file.isFile)
            assertTrue(file.readText().contains("Exclude: true"))
            "2 compiler directives added\n"
        }
        assertFalse(directiveFile!!.exists())
    }

    @Test
    fun rejectsFailurePartialAndEmptyResponsesAndDeletesFile() {
        for (response in listOf("Could not load file: missing.json", "1 compiler directives added", "")) {
            var directiveFile: File? = null
            val error = assertThrows(IllegalStateException::class.java) {
                JitWorkaround.installDirectives { fileName ->
                    directiveFile = File(fileName)
                    response
                }
            }
            assertTrue(error.message!!.contains("JVM did not accept both C2 directives"))
            assertFalse(directiveFile!!.exists())
        }
    }

    @Test
    fun deletesFileWhenDiagnosticCommandThrows() {
        var directiveFile: File? = null
        val expected = IOException("Diagnostic command unavailable")
        val error = assertThrows(IOException::class.java) {
            JitWorkaround.installDirectives { fileName ->
                directiveFile = File(fileName)
                throw expected
            }
        }
        assertSame(expected, error)
        assertFalse(directiveFile!!.exists())
    }

    private fun diagnosticCommand(operation: String): String =
        ManagementFactory.getPlatformMBeanServer().invoke(
            ObjectName("com.sun.management:type=DiagnosticCommand"), operation, null, null,
        ).toString()
}
