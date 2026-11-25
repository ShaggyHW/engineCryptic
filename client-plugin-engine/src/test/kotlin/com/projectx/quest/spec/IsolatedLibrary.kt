package com.projectx.quest.spec

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files

/**
 * Runs a [IsolatedLibraryScenarios] method against a throwaway project root. The library resolves its
 * directories once per class load, so each run gets fresh copies of every class in its own loader.
 */
object IsolatedLibrary {
    private val OVERRIDES = listOf("PROJECTX_QUEST_DIR", "PROJECTX_QUEST_DATA", "PROJECTX_PROJECT_ROOT")

    val homeQuestDir: File get() = File(System.getProperty("user.home"), ".projectx/quest-data/quests")

    fun newRoot(): File {
        val root = Files.createTempDirectory("quest-spec").toFile()
        File(root, "settings.gradle.kts").writeText("")
        File(root, "re-resources").mkdirs()
        return root
    }

    fun questDir(root: File): File = File(root, "client-plugin-engine/src/main/resources/quest-data/quests")

    fun legacyBundle(root: File): File = File(root, "client-plugin-engine/src/main/resources/quest-data/quests.json")

    fun run(scenario: String, root: File) {
        assumeTrue(OVERRIDES.all { System.getenv(it) == null }, "quest path overrides set in the environment")
        val urls = System.getProperty("java.class.path").split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it).toURI().toURL() }
            .toTypedArray()
        val loader = URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
        val thread = Thread.currentThread()
        val previousLoader = thread.contextClassLoader
        val previousDir = System.getProperty("user.dir")
        System.setProperty("user.dir", root.absolutePath)
        thread.contextClassLoader = loader
        try {
            val type = loader.loadClass(IsolatedLibraryScenarios::class.java.name)
            type.getMethod(scenario, String::class.java).invoke(type.getDeclaredConstructor().newInstance(), root.absolutePath)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        } finally {
            System.setProperty("user.dir", previousDir)
            thread.contextClassLoader = previousLoader
        }
    }

    fun discard(root: File) {
        root.deleteRecursively()
    }
}
