package com.projectx.util.spec

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.projectx.util.JsonFileManager
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JsonFileManagerSpecTest {

    data class Sample(val name: String = "", val count: Int = 0, val tags: List<String> = emptyList(), val note: String? = null)

    private val dir: File = Files.createTempDirectory("json-spec").toFile()
    private val original = JsonFileManager.getGson()

    @AfterTest
    fun cleanUp() {
        JsonFileManager.setGson(original)
        dir.deleteRecursively()
    }

    @Test
    fun `saved files are pretty printed UTF-8 with two space indents and no nulls`() {
        val file = File(dir, "sample.json")
        JsonFileManager.saveJsonFile(Sample("ünïcode ✓", 3, listOf("a")), file)
        val text = file.readText(Charsets.UTF_8)
        assertEquals("{\n  \"name\": \"ünïcode ✓\",\n  \"count\": 3,\n  \"tags\": [\n    \"a\"\n  ]\n}", text)
    }

    @Test
    fun `save creates missing parent folders and load reads the object back`() {
        val file = File(dir, "a/b/c/sample.json")
        val sample = Sample("x", 7, listOf("p", "q"), "n")
        JsonFileManager.saveJsonFile(sample, file)
        assertTrue(file.isFile)
        assertEquals(sample, JsonFileManager.loadJsonFile<Sample>(file, Sample::class.java))
    }

    @Test
    fun `saving overwrites the previous contents`() {
        val file = File(dir, "sample.json")
        JsonFileManager.saveJsonFile(Sample("long name here", 1), file)
        JsonFileManager.saveJsonFile(Sample("s", 2), file)
        assertEquals(Sample("s", 2), JsonFileManager.loadJsonFile<Sample>(file, Sample::class.java))
    }

    @Test
    fun `loading honours generic types`() {
        val file = File(dir, "list.json").apply { writeText("""[{"name":"a","count":1},{"name":"b"}]""") }
        val type = object : TypeToken<List<Sample>>() {}.type
        assertEquals(listOf(Sample("a", 1), Sample("b", 0)), JsonFileManager.loadJsonFile<List<Sample>>(file, type))
    }

    @Test
    fun `loading a missing file fails`() {
        assertFailsWith<IllegalStateException> {
            JsonFileManager.loadJsonFile<Sample>(File(dir, "missing.json"), Sample::class.java)
        }
    }

    @Test
    fun `an empty file loads as null and malformed json throws`() {
        val empty = File(dir, "empty.json").apply { writeText("") }
        assertNull(JsonFileManager.loadJsonFile<Sample?>(empty, Sample::class.java))
        val broken = File(dir, "broken.json").apply { writeText("{\"name\": ") }
        assertFailsWith<JsonSyntaxException> { JsonFileManager.loadJsonFile<Sample>(broken, Sample::class.java) }
    }

    @Test
    fun `string conversion uses the same gson`() {
        assertEquals("{\n  \"name\": \"a\",\n  \"count\": 1,\n  \"tags\": []\n}", JsonFileManager.toJson(Sample("a", 1)))
        assertEquals(Sample("z", 9), JsonFileManager.fromJsonString<Sample>("""{"name":"z","count":9}""", Sample::class.java))
    }

    @Test
    fun `the gson instance can be replaced`() {
        val compact = GsonBuilder().serializeNulls().create()
        JsonFileManager.setGson(compact)
        assertSame(compact, JsonFileManager.getGson())
        assertEquals("""{"name":"a","count":1,"tags":[],"note":null}""", JsonFileManager.toJson(Sample("a", 1)))
    }
}
