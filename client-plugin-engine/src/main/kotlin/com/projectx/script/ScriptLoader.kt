package com.projectx.script

import com.projectx.store.EncryptedScripts
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Paths

enum class ScannerType {
    JAR,
    CLASS,
    BOTH
}

interface ClassScanner {
    fun scan(): List<Class<*>>
}

sealed class AbstractClassScanner : ClassScanner {
    abstract class PathClassScanner(
        private val directoryPath: String,
    ) : AbstractClassScanner() {
        protected fun getAbsolutePaths(relativePaths: Array<String>?): List<String> =
            relativePaths?.map { path ->
                Paths.get(directoryPath, path).toString()
            } ?: emptyList()
    }

    abstract class DefaultClassScanner : AbstractClassScanner()
}

object FileExtensions {
    const val JAR = ".jar"
    const val CLASS = ".class"
}

class JarDirectoryScanner(
    private val directoryPath: String
) : AbstractClassScanner.PathClassScanner(directoryPath) {

    override fun scan(): List<Class<*>> {
        val dir = File(directoryPath)
        // A `.pxe` an older launcher saved goes into memory, and off the disk, before anything is listed.
        runCatching { EncryptedScripts.adopt(dir) }
        val plain = runCatching { scanJarFiles(getAbsolutePaths(findJarFiles())) }.getOrDefault(emptyList())
        val encrypted = runCatching {
            EncryptedScripts.loaded(dir).flatMap { scriptClassesIn(it.classNames, it.loader) }
        }.getOrDefault(emptyList())
        return plain + encrypted
    }

    // A plaintext jar of a release whose encrypted build is held is a leftover, not a second copy to load.
    private fun findJarFiles(): Array<String>? {
        val names = File(directoryPath).list() ?: return null
        val held = EncryptedScripts.heldJarNames()
        return names.filter { name -> name.lowercase().endsWith(FileExtensions.JAR) && name !in held }.toTypedArray()
    }

    // Enumerate class NAMES from the jars (bytecode only, no loading), then load each through a
    // classloader parented to the engine's loader so a script's engine/core superclasses & deps
    // resolve. Detecting subclasses by reflection (not ClassGraph's jar-only graph) catches
    // transitive Script subclasses (e.g. StateMachineScript-based). Per-class failures are isolated
    // so one bad script can't drop the whole jar. initialize=false avoids running <clinit> at scan.
    private fun scanJarFiles(sourceJarPaths: List<String>): List<Class<*>> = scriptClassesInJars(sourceJarPaths)

    private fun scriptClassesIn(classNames: List<String>, loader: ClassLoader): List<Class<*>> = scriptClassesNamed(classNames, loader)
}

internal fun scriptClassesInJars(sourceJarPaths: List<String>): List<Class<*>> {
    if (sourceJarPaths.isEmpty()) return emptyList()
    val jarPaths = ScriptJarShadow.copiesOf(sourceJarPaths)
    val urls = jarPaths.map { File(it).toURI().toURL() }.toTypedArray()
    val loader = URLClassLoader(urls, Script::class.java.classLoader)
    val classNames = ClassGraph()
        .enableClassInfo()
        .overrideClasspath(jarPaths)
        .scan()
        .use { it.allClasses.names }
    return scriptClassesNamed(classNames, loader)
}

internal fun scriptClassesNamed(classNames: List<String>, loader: ClassLoader): List<Class<*>> =
    classNames.mapNotNull { name ->
        runCatching { Class.forName(name, false, loader) }.getOrNull()
            ?.takeIf {
                Script::class.java.isAssignableFrom(it) &&
                    it != Script::class.java &&
                    !Modifier.isAbstract(it.modifiers)
            }
    }

/**
 * A developer's own scripts in `~/.projectx/scripts/dev`: every plain jar and class file there, exactly as built. The
 * store never writes or cleans this folder, so nothing here is filtered as a leftover of a store build.
 */
internal class LocalScriptsScanner(private val directory: File) : ClassScanner {
    override fun scan(): List<Class<*>> {
        val jars = directory.listFiles { file -> file.isFile && file.name.lowercase().endsWith(FileExtensions.JAR) }
            .orEmpty().map { it.absolutePath }.sorted()
        val fromJars = runCatching { scriptClassesInJars(jars) }.getOrDefault(emptyList())
        return fromJars + ClassFileScanner(directory.absolutePath).scan()
    }
}

/**
 * Script jars are loaded from private copies, never from the scripts folder itself. A loader keeps its jars open for
 * as long as its classes live, and Windows will not replace a file another process has open: the launcher could not
 * update a script channel while any client ran. A jar rewritten underneath a loader that already opened it also reads
 * back as corrupt ("Truncated class file"). Each copy is keyed by its source's size and modification time, so an
 * updated jar gets a fresh copy while earlier copies stay untouched for loaders still using them.
 */
internal object ScriptJarShadow {
    private val dir: File
        get() = File(System.getProperty("user.home"), ".projectx/cache/script-jars")

    fun copiesOf(sourcePaths: List<String>): List<String> {
        val target = dir
        target.mkdirs()
        val copies = sourcePaths.map { copyOf(File(it), target) }
        removeUnused(target, copies.map { File(it).name }.toSet())
        return copies
    }

    private fun copyOf(source: File, target: File): String {
        val copy = File(target, "${source.nameWithoutExtension}-${source.length()}-${source.lastModified()}.jar")
        if (copy.isFile && copy.length() == source.length()) return copy.absolutePath
        return runCatching {
            val partial = File(target, "${copy.name}.part")
            source.copyTo(partial, overwrite = true)
            if (!partial.renameTo(copy)) {
                partial.delete()
                error("could not move ${partial.name} into place")
            }
            copy.absolutePath
        }.getOrElse {
            println("[ScriptLoader] loading ${source.name} in place, could not copy it: ${it.message}")
            source.absolutePath
        }
    }

    /** Copies an earlier engine or scan still has open cannot be deleted yet; they go on a later scan. */
    private fun removeUnused(target: File, inUse: Set<String>) {
        target.listFiles()?.filter { it.name !in inUse }?.forEach { it.delete() }
    }
}

class ClassFileScanner(
    private val directoryPath: String
) : AbstractClassScanner.PathClassScanner(directoryPath) {

    override fun scan(): List<Class<*>> = runCatching { scanClassFiles() }
        .fold(
            onSuccess = { it },
            onFailure = { emptyList() }
        )

    private fun scanClassFiles(): List<Class<*>> =
        ClassGraph()
            .enableAllInfo()
            .overrideClasspath(directoryPath)
            .scan()
            .getSubclasses(Script::class.java.name)
            .filter { it.isClassFile() }
            .loadClasses()

    private fun ClassInfo.isClassFile(): Boolean =
        resource.path.endsWith(FileExtensions.CLASS)
}

class CurrentProjectScriptsScanner : AbstractClassScanner.DefaultClassScanner() {

    override fun scan(): List<Class<*>> = runCatching { scanClassFiles() }
        .fold(
            onSuccess = { it },
            onFailure = { emptyList() }
        )

    private fun scanClassFiles(): List<Class<*>> =
        ClassGraph()
            .enableClassInfo()
            .enableAnnotationInfo()
            .scan()
            .getSubclasses(Script::class.java.name)
            .loadClasses()
}

class CombinedScanner(
    directoryPath: String
) : AbstractClassScanner() {

    private val jarScanner = JarDirectoryScanner(directoryPath)
    private val classFileScanner = ClassFileScanner(directoryPath)

    override fun scan(): List<Class<*>> = runCatching {
        val jarClasses = jarScanner.scan()
        val classFileClasses = classFileScanner.scan()

        jarClasses + classFileClasses
    }.fold(
        onSuccess = { it },
        onFailure = { emptyList() }
    )
}

object ClassScannerFactory {
    fun create(path: String, type: ScannerType): ClassScanner =
        when (type) {
            ScannerType.JAR -> JarDirectoryScanner(path)
            ScannerType.CLASS -> ClassFileScanner(path)
            ScannerType.BOTH -> CombinedScanner(path)
        }
}