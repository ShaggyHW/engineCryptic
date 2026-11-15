package com.projectx.store

import com.projectx.script.InMemoryJar
import com.projectx.script.InMemoryJarClassLoader
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.function.Supplier
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EncryptedFixture : Supplier<String> {
    override fun get(): String = "loaded from memory"
}

class EncryptedScriptsTest {
    private lateinit var dir: File
    private lateinit var server: HttpServer
    private var keyStatus = 200
    private var servedKey: ByteArray = ByteArray(32)
    private var lastKeyPath: String? = null
    private var lastKeyQuery: String? = null
    private var lastAuth: String? = null

    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @BeforeTest
    fun setUp() {
        EncryptedScripts.forgetAll()
        dir = Files.createTempDirectory("projectx-pxe").toFile()
        servedKey = key
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/hub/jar-keys/") { exchange ->
            lastKeyPath = exchange.requestURI.rawPath
            lastKeyQuery = exchange.requestURI.rawQuery
            lastAuth = exchange.requestHeaders.getFirst("Authorization")
            val body = """{"format":"pxe1","key":"${Base64.getEncoder().encodeToString(servedKey)}"}""".toByteArray()
            exchange.sendResponseHeaders(keyStatus, if (keyStatus == 200) body.size.toLong() else -1)
            if (keyStatus == 200) exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
        EncryptedScripts.forgetAll()
    }

    private val storeBase get() = "http://127.0.0.1:${server.address.port}"

    private fun fixtureJar(): ByteArray {
        val classFile = "com/projectx/store/EncryptedFixture.class"
        val classBytes = javaClass.classLoader.getResourceAsStream(classFile)!!.use { it.readAllBytes() }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\n".toByteArray())
            zip.putNextEntry(ZipEntry("com/projectx/store/"))
            zip.putNextEntry(ZipEntry(classFile))
            zip.write(classBytes)
            zip.putNextEntry(ZipEntry("icons/fixture icon.txt"))
            zip.write("an icon".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun encrypt(plain: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return "PXE1".toByteArray() + nonce + cipher.doFinal(plain)
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun held(plain: ByteArray = fixtureJar(), sha256: String? = sha(plain)): HeldBuild =
        EncryptedScripts.hold(PxeRecord("GatesOfElidinis", "3.6.2", sha256), encrypt(plain, key, Pxe1.aad("GatesOfElidinis", "3.6.2")))

    // What an engine before memory-only loading, or an older launcher, left in the scripts folder.
    private fun savedByAnOlderBuild(plain: ByteArray = fixtureJar()): File {
        val pxe = File(dir, ScriptSync.pxeName("GatesOfElidinis", "3.6.2"))
        pxe.writeBytes(encrypt(plain, key, Pxe1.aad("GatesOfElidinis", "3.6.2")))
        File(dir, PxeRecord.fileFor(pxe.name)).writeText(PxeRecord("GatesOfElidinis", "3.6.2", sha(plain)).encode())
        return pxe
    }

    private fun load(build: HeldBuild) =
        EncryptedScripts.load(build, dir, "tok", HttpClient.newHttpClient(), storeBase, ClassLoader.getPlatformClassLoader())

    private fun holdsGates() = EncryptedScripts.holds("GatesOfElidinis", "3.6.2")

    @Test
    fun `decrypts the shared test vector`() {
        val key = ByteArray(32) { it.toByte() }
        val blob = HexFormat.of().parseHex(
            "50584531a0a1a2a3a4a5a6a7a8a9aaabb6535c5920b8769f0804f5f36503b4bb03474ce6876d9f09c0d234acad2082269b"
        )

        val plain = Pxe1.decrypt(blob, key, Pxe1.aad("TestScript", "1.2.3", game = "OSRS"))

        assertEquals("PK test jar bytes", plain.decodeToString())
    }

    @Test
    fun `a blob passed off as another game, script or release does not decrypt`() {
        val key = ByteArray(32) { it.toByte() }
        val blob = HexFormat.of().parseHex(
            "50584531a0a1a2a3a4a5a6a7a8a9aaabb6535c5920b8769f0804f5f36503b4bb03474ce6876d9f09c0d234acad2082269b"
        )
        assertFailsWith<AEADBadTagException> { Pxe1.decrypt(blob, key, Pxe1.aad("TestScript", "1.2.3")) }
        assertFailsWith<AEADBadTagException> { Pxe1.decrypt(blob, key, Pxe1.aad("TestScript", "1.2.4", game = "OSRS")) }
    }

    @Test
    fun `only bytes that start with the magic count as an encrypted build`() {
        assertFalse(Pxe1.isBlob("PK\u0003\u0004 a plain jar with plenty of bytes".toByteArray()))
        assertFalse(Pxe1.isBlob("PXE1".toByteArray()))
        assertTrue(Pxe1.isBlob(encrypt("x".toByteArray(), key, ByteArray(0))))
    }

    @Test
    fun `the format is added to a download url with or without a query`() {
        assertEquals("https://x/jar/A/1?game=rs3&format=pxe1", Pxe1.withFormat("https://x/jar/A/1?game=rs3"))
        assertEquals("https://x/jar/A/1?format=pxe1", Pxe1.withFormat("https://x/jar/A/1"))
    }

    @Test
    fun `the engine jar says it reads encrypted scripts, which is what the launcher checks`() {
        assertNotNull(javaClass.classLoader.getResource("projectx/capabilities/script-format-pxe1"))
    }

    @Test
    fun `the engine jar says it keeps paid scripts in memory, so the launcher leaves them to it`() {
        assertNotNull(javaClass.classLoader.getResource("projectx/capabilities/script-memory-only"))
    }

    @Test
    fun `the in-memory loader serves classes and resources from the jar`() {
        val jar = InMemoryJar.read(fixtureJar())
        val loader = InMemoryJarClassLoader(jar, ClassLoader.getPlatformClassLoader(), null)

        assertEquals(listOf("com.projectx.store.EncryptedFixture"), jar.classNames)
        val type = Class.forName("com.projectx.store.EncryptedFixture", true, loader)
        assertSame(loader, type.classLoader)
        assertEquals("loaded from memory", (type.getDeclaredConstructor().newInstance() as Supplier<*>).get())

        val url = assertNotNull(loader.getResource("icons/fixture icon.txt"))
        assertEquals(InMemoryJarClassLoader.PROTOCOL, url.protocol)
        assertEquals("an icon", url.openStream().use { it.readAllBytes().decodeToString() })
        assertEquals("an icon", loader.getResourceAsStream("icons/fixture icon.txt")!!.use { it.readAllBytes().decodeToString() })
        assertEquals(1, loader.getResources("icons/fixture icon.txt").toList().size)
        assertEquals(url, loader.getResource("icons/fixture icon.txt"))
        assertEquals(url.hashCode(), loader.getResource("icons/fixture icon.txt")!!.hashCode())
        assertNull(loader.getResource("icons/missing.txt"))
        assertFailsWith<ClassNotFoundException> { loader.loadClass("com.projectx.store.Missing") }
    }

    @Test
    fun `an owned encrypted script loads from memory with a key fetched for it`() {
        val build = held()

        val outcome = assertIs<EncryptedScripts.Outcome.Ready>(load(build))

        assertEquals(listOf("com.projectx.store.EncryptedFixture"), outcome.loaded.classNames)
        val type = Class.forName(outcome.loaded.classNames.single(), false, outcome.loaded.loader)
        assertSame(outcome.loaded.loader, type.classLoader)
        // The script library reads the origin from this name; the file itself is never written.
        assertEquals("GatesOfElidinis-3.6.2.pxe", File(type.protectionDomain.codeSource.location.toURI()).name)
        assertEquals("/api/v1/hub/jar-keys/GatesOfElidinis/3.6.2", lastKeyPath)
        assertEquals("game=rs3", lastKeyQuery)
        assertEquals("Bearer tok", lastAuth)
        assertTrue(holdsGates())
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun `a refused key skips the script and keeps the build`() {
        keyStatus = 403
        val build = held()

        val outcome = assertIs<EncryptedScripts.Outcome.Skipped>(load(build))

        assertTrue("refused its key (403)" in outcome.message, outcome.message)
        assertTrue(holdsGates())
    }

    @Test
    fun `a key that does not open the build drops it so the next sync downloads it again`() {
        servedKey = ByteArray(32) { 7 }
        val build = held()

        assertIs<EncryptedScripts.Outcome.Discarded>(load(build))

        assertFalse(holdsGates())
    }

    @Test
    fun `a store that cannot be reached skips the script this time and keeps the build`() {
        val build = held()
        server.stop(0)

        assertIs<EncryptedScripts.Outcome.Skipped>(load(build))

        assertTrue(holdsGates())
    }

    @Test
    fun `a decrypted build that does not match its published checksum is not loaded`() {
        val build = held(sha256 = sha("another build".toByteArray()))

        assertIs<EncryptedScripts.Outcome.Discarded>(load(build))

        assertFalse(holdsGates())
    }

    @Test
    fun `dropping a bad build leaves a newer download of the script alone`() {
        servedKey = ByteArray(32) { 7 }
        val bad = held()
        val newer = held()

        assertIs<EncryptedScripts.Outcome.Discarded>(load(bad))

        assertTrue(holdsGates())
        assertSame(newer.record, EncryptedScripts.heldRecord("GatesOfElidinis"))
    }

    @Test
    fun `a build an older launcher saved moves into memory and off the disk`() {
        savedByAnOlderBuild()

        EncryptedScripts.adopt(dir)

        assertTrue(dir.list()!!.isEmpty())
        assertTrue(holdsGates())
        assertIs<EncryptedScripts.Outcome.Ready>(load(assertNotNull(EncryptedScripts.heldBuild("GatesOfElidinis"))))
    }

    @Test
    fun `a saved build without its record is deleted rather than guessed at`() {
        val pxe = savedByAnOlderBuild()
        File(dir, PxeRecord.fileFor(pxe.name)).delete()
        File(dir, "Rasial-2.4.0.pxe.json").writeText(PxeRecord("Rasial", "2.4.0", null).encode())

        EncryptedScripts.adopt(dir)

        assertTrue(dir.list()!!.isEmpty())
        assertFalse(holdsGates())
        assertEquals(null, lastAuth)
    }

    @Test
    fun `the record keeps the real names through a round trip`() {
        val record = PxeRecord("Gates Of+Elidinis", "3.6.2-beta", "abc")
        assertEquals(record, PxeRecord.decode(record.encode()))
        assertEquals(PxeRecord("A", "1", null), PxeRecord.decode("""{"game":"RS3","internalName":"A","version":"1"}"""))
        assertNull(PxeRecord.decode("not json"))
    }
}
