package com.projectx.testsupport

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/**
 * Files a test reads from the checkout rather than the classpath. Some live outside this repository and are not on
 * every machine, Linux or Windows, CI included: a test that needs one is skipped where it is missing rather than
 * failed, so the suite passes on any checkout.
 */
object RepositoryFiles {

    /** The checkout's root: the folder holding the root Gradle settings and the engine module. */
    val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "client-plugin-engine").isDirectory }
            ?: error("could not locate the repository root from ${File("").absolutePath}")
    }

    /** The ai-input training spec directory, which is kept outside this repository. */
    val trainingSpecDir: File get() = File(root, "ai-input/spec")

    /** [name] from the ai-input training spec; skips the test on a machine without it. */
    fun trainingSpec(name: String): File {
        val file = File(trainingSpecDir, name)
        assumeTrue(file.isFile, "ai-input/spec/$name is not on this machine; the training spec lives outside this repository")
        return file
    }

    /** [path] under the root from a submodule; skips the test when that submodule is not checked out. */
    fun submoduleFile(path: String): File {
        val file = File(root, path)
        assumeTrue(file.isFile, "$path is missing; run git submodule update --init --recursive")
        return file
    }
}
