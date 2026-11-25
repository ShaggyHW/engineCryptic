package com.projectx.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonIOException
import java.io.File
import java.io.IOException
import java.lang.reflect.Type

object JsonFileManager {

    @Volatile
    private var current: Gson = GsonBuilder().setPrettyPrinting().create()

    fun setGson(gson: Gson) {
        current = gson
    }

    fun getGson(): Gson = current

    @Throws(IOException::class)
    fun <T> loadJsonFile(file: File, type: Type): T {
        check(file.exists()) { "File ${file.absolutePath} does not exist" }
        return file.bufferedReader(Charsets.UTF_8).use { current.fromJson(it, type) }
    }

    @Throws(JsonIOException::class)
    fun <T> fromJsonString(json: String, type: Type): T = current.fromJson(json, type)

    @Throws(IOException::class)
    fun saveJsonFile(data: Any, file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        val gson = current
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            val writer = gson.newJsonWriter(out)
            writer.setIndent("  ")
            gson.toJson(data, data.javaClass, writer)
            writer.flush()
        }
    }

    fun toJson(data: Any): String = current.toJson(data)
}
