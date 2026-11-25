package com.projectx.script

import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ScriptDescription(name = "Local scanner fixture", version = "1.0.0", author = "test", description = "A script in a jar in the dev folder.")
class LocalScannerFixtureScript : Script() {
    override suspend fun loop() = Unit
}

class LocalScriptsScannerTest {
    private lateinit var home: File
    private lateinit var dev: File
    private var savedHome: String? = null

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("local-scripts-home").toFile()
        dev = File(home, ".projectx/scripts/dev").apply { mkdirs() }
        savedHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        savedHome?.let { System.setProperty("user.home", it) }
        home.deleteRecursively()
    }

    private fun jarOf(name: String, vararg classes: Class<*>): File {
        val jar = File(dev, name)
        JarOutputStream(jar.outputStream()).use { out ->
            for (c in classes) {
                val path = c.name.replace('.', '/') + ".class"
                out.putNextEntry(JarEntry(path))
                c.classLoader.getResourceAsStream(path)!!.use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `finds the scripts in a jar in the dev folder`() {
        jarOf("fixture-1.0.0.jar", LocalScannerFixtureScript::class.java)
        val found = LocalScriptsScanner(dev).scan().map { it.name }
        assertEquals(listOf(LocalScannerFixtureScript::class.java.name), found)
    }

    @Test
    fun `an empty dev folder finds nothing`() {
        assertTrue(LocalScriptsScanner(dev).scan().isEmpty())
    }

    @Test
    fun `the dev folder is where ScriptExecutor looks for local builds`() {
        assertEquals(dev.canonicalFile, ScriptExecutor.localScriptsDirectory.canonicalFile)
    }
}
