package com.projectx.util

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Logger {

    private const val ERROR_LOG = "errorLog.log"
    private val dateFormat = DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss")

    fun getDateString(): String = LocalDateTime.now().format(dateFormat)

    fun handle(throwable: Throwable) {
        logError(throwable)
        System.err.println("ERROR! THREAD NAME: ${Thread.currentThread().name}")
        throwable.printStackTrace()
    }

    fun log(instance: Any, message: Any) = log(instance.javaClass.simpleName, message)

    fun log(className: String, message: Any) = println("[$className] $message")

    fun writeToFile(fileName: String, text: String) {
        runCatching {
            val target = File(File(System.getProperty("user.home"), ".projectx"), fileName)
            target.parentFile.mkdirs()
            target.appendText("[${getDateString()}]:  $text\r\n", Charsets.UTF_8)
        }
    }

    fun logError(throwable: Throwable) = writeToFile(ERROR_LOG, "${Thread.currentThread().name}: ${throwable.stackTraceToString()}")

    fun logError(message: String) = writeToFile(ERROR_LOG, message)
}
