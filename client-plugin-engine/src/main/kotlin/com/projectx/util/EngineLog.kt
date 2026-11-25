package com.projectx.util

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

object EngineLog {

    const val MAX_LINES = 100

    val lines = CopyOnWriteArrayList<String>()

    var file: File? = null
        private set

    private val stampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val fileNameFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
    private val processStdout = PrintStream(FileOutputStream(FileDescriptor.out), true)
    private var capture: PrintStream? = null

    fun install() {
        try {
            val folder = File(File(System.getProperty("user.home"), ".projectx"), "logs")
            folder.mkdirs()
            val pid = ProcessHandle.current().pid()
            val now = LocalDateTime.now()
            val target = File(folder, "projectx-${now.format(fileNameFormat)}-$pid.log")
            file = target
            lines += "Logging Initialized at ${now.format(stampFormat)}"
            val stream = StampingStream(FileOutputStream(target, false))
            capture = stream
            System.setOut(stream)
            System.setErr(stream)
            println("[Engine] ${EngineBuild.describe()} pid $pid")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun close() {
        System.setOut(processStdout)
        System.setErr(processStdout)
        capture?.close()
        capture = null
    }

    private fun remember(line: String) {
        lines += line
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    private class StampingStream(sink: OutputStream) : PrintStream(sink, true, Charsets.UTF_8) {

        override fun println(x: String?) = emit(x.toString())

        override fun println(x: Any?) = emit(x.toString())

        override fun println() = emit("")

        private fun emit(text: String) {
            val line = "[${LocalDateTime.now().format(stampFormat)}] $text"
            synchronized(this) {
                super.println(line)
                processStdout.println(line)
                remember(line)
            }
        }
    }
}
