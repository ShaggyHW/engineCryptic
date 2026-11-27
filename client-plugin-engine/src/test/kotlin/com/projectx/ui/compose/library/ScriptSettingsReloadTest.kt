package com.projectx.ui.compose.library

import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigHolder
import com.projectx.script.ConfigurableScript
import com.projectx.script.Script
import com.projectx.script.ScriptConfigStore
import com.projectx.script.ScriptDescription
import com.projectx.script.ScriptMetadata
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ReloadedSettingsHolder : ConfigHolder {
    val leaveEarly = BooleanConfigItem("Leave early", "", false)
}

@ScriptDescription(name = "Reloaded settings fixture", version = "1.0.0", author = "test", description = "")
class ReloadedSettingsFixtureScript : Script(), ConfigurableScript {
    val config = ReloadedSettingsHolder()
    override suspend fun loop() = Unit
}

/** Defines its own copy of the named classes, as a rebuilt script jar does on Refresh. */
private class RebuiltJarLoader(parent: ClassLoader, private val names: Set<String>) : ClassLoader(parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name !in names) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }
            return defineClass(name, bytes, 0, bytes.size)
        }
    }
}

class ScriptSettingsReloadTest {
    private lateinit var dir: File
    private lateinit var previous: File

    @BeforeTest
    fun useTempDirectory() {
        previous = ScriptConfigStore.directory
        dir = Files.createTempDirectory("settings-reload").toFile()
        ScriptConfigStore.directory = dir
    }

    @AfterTest
    fun restoreDirectory() {
        ScriptConfigStore.directory = previous
        dir.deleteRecursively()
    }

    private fun metaOf(scriptClass: Class<out Script>) = ScriptMetadata(scriptClass, "Reloaded settings fixture", "1.0.0", "test", "")

    private fun rebuilt(): Class<out Script> {
        val original = ReloadedSettingsFixtureScript::class.java
        val loader = RebuiltJarLoader(original.classLoader, setOf(original.name, ReloadedSettingsHolder::class.java.name))
        @Suppress("UNCHECKED_CAST")
        return loader.loadClass(original.name) as Class<out Script>
    }

    @Test
    fun `the same build keeps its draft between views`() {
        val meta = metaOf(ReloadedSettingsFixtureScript::class.java)
        assertSame(ScriptSettings.instanceFor(meta), ScriptSettings.instanceFor(meta))
    }

    @Test
    fun `a rebuilt script gets settings from its new build, not the draft of the old one`() {
        val before = ScriptSettings.instanceFor(metaOf(ReloadedSettingsFixtureScript::class.java))!!
        val rebuiltClass = rebuilt()

        val after = ScriptSettings.instanceFor(metaOf(rebuiltClass))!!

        assertNotSame<Class<*>>(before.javaClass, rebuiltClass)
        assertSame<Class<*>>(rebuiltClass, after.javaClass)
    }
}
