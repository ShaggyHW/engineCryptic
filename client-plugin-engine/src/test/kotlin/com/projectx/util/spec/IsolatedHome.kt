package com.projectx.util.spec

import com.projectx.markers.TileMarkerStore
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files

/**
 * A throwaway user home plus a fresh copy of the util and markers singletons for each scenario run.
 *
 * Those singletons fix their `~/.projectx` paths when first touched and keep their state for the life of the JVM,
 * so scenarios run inside their own class loader: the real home is never read or written, and every run starts
 * from whatever is on disk in this home.
 */
class IsolatedHome : AutoCloseable {
    val dir: File = Files.createTempDirectory("projectx-spec-home").toFile()
    val projectx: File get() = File(dir, ".projectx")

    private val savedHome = System.getProperty("user.home")

    init {
        System.setProperty("user.home", dir.absolutePath)
    }

    fun run(scenarios: String, method: String, vararg args: String) {
        // Left open: threads a scenario starts may still load classes after it returns.
        val type = Class.forName(scenarios, true, ChildFirstLoader())
        val instance = type.getDeclaredConstructor().newInstance()
        val target = type.methods.single { it.name == method && it.parameterCount == args.size }
        try {
            target.invoke(instance, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        } finally {
            (instance as? AutoCloseable)?.close()
        }
    }

    override fun close() {
        System.setProperty("user.home", savedHome)
        dir.deleteRecursively()
    }

    private class ChildFirstLoader : URLClassLoader(
        arrayOf(
            TileMarkerStore::class.java.protectionDomain.codeSource.location,
            IsolatedHome::class.java.protectionDomain.codeSource.location,
        ),
        IsolatedHome::class.java.classLoader,
    ) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!name.startsWith("com.projectx.util.") && !name.startsWith("com.projectx.markers.")) {
                return super.loadClass(name, resolve)
            }
            synchronized(getClassLoadingLock(name)) {
                val type = findLoadedClass(name) ?: try {
                    findClass(name)
                } catch (_: ClassNotFoundException) {
                    super.loadClass(name, false)
                }
                if (resolve) resolveClass(type)
                return type
            }
        }
    }
}
