package com.projectx.javaapi

import com.projectx.script.Wait
import io.github.classgraph.ClassGraph
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlin.reflect.KVisibility
import kotlin.reflect.jvm.kotlinFunction
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Java scripts get the whole API. Two kinds of Kotlin member compile to something Java cannot use: a `suspend fun`,
 * which Java could call but never wait on, so it is hidden from Java with `@JvmSynthetic`, and a member taking or
 * returning a `Tile`, whose JVM name is mangled. Each needs a Java form: a [Wait] of the same name, a same-named
 * overload over coordinates, or an entry in [JAVA_FORMS].
 */
class JavaReachabilityTest {

    @Test
    fun `every helper that waits has a Wait`() {
        val missing = surfaceMethods()
            .filter { it.isSuspend() && !it.isAbstractOrOverridable() }
            .filterNot { hasWait(it.baseName()) || mapped(it) != null }
            .map { it.describe() }
            .distinct()
            .sorted()
        assertTrue(missing.isEmpty(), "Suspend helpers Java cannot wait on; add a Wait factory of the same name:\n" + missing.joinToString("\n"))
    }

    @Test
    fun `Java cannot call a helper that waits by mistake`() {
        val visible = surfaceMethods()
            .filter { it.isSuspend() && !it.isAbstractOrOverridable() && !it.isSynthetic && !it.overridesInherited() }
            .map { it.describe() }
            .distinct()
            .sorted()
        assertTrue(visible.isEmpty(), "Suspend helpers Java can call but never wait on; mark them @JvmSynthetic:\n" + visible.joinToString("\n"))
    }

    @Test
    fun `every member named for Kotlin has a form Java can call`() {
        val missing = surfaceMethods()
            .filter { it.isMangled() && !it.isSuspend() && !it.isDataClassMember() }
            .filterNot { hasPlainForm(it) || mapped(it) != null }
            .map { it.describe() }
            .distinct()
            .sorted()
        assertTrue(missing.isEmpty(), "Members Java cannot call; add a coordinate overload of the same name:\n" + missing.joinToString("\n"))
    }

    @Test
    fun `scripts implement Kotlin-only types through their Java counterparts`() {
        val missing = surfaceMethods()
            .filter { it.isSuspend() && it.isAbstractOrOverridable() }
            .filterNot { mapped(it) != null }
            .map { it.describe() }
            .distinct()
            .sorted()
        assertTrue(missing.isEmpty(), "Suspend members a Java script would have to implement:\n" + missing.joinToString("\n"))
    }

    @Test
    fun `every named Java form exists`() {
        val broken = JAVA_FORMS.values.distinct().filterNot { target ->
            val (owner, member) = target.split('#')
            val type = runCatching { Class.forName(owner, false, javaClass.classLoader) }.getOrNull() ?: return@filterNot false
            generateSequence(type) { it.superclass }.any { owner ->
                owner.declaredMethods.any { it.name == member && !it.isSynthetic && !Modifier.isPrivate(it.modifiers) }
            }
        }
        if (broken.isNotEmpty()) fail("JAVA_FORMS names members that do not exist: $broken")
    }

    private fun hasWait(name: String) = WAIT_FACTORIES.contains(name.lowercase())

    private fun hasPlainForm(method: Method): Boolean {
        val base = method.baseName()
        return method.declaringClass.methods.any {
            !it.isSynthetic && !it.isMangled() && (it.name == base || it.name == base + "X" || it.name == "get" + base.replaceFirstChar(Char::uppercase) + "X")
        }
    }

    private fun mapped(method: Method): String? =
        JAVA_FORMS["${method.declaringClass.name}#${method.baseName()}"]

    private fun surfaceMethods(): List<Method> = SURFACE.flatMap { type ->
        type.declaredMethods.filter { it.isPublicApi() }
    }

    private fun Method.isPublicApi(): Boolean {
        if (!Modifier.isPublic(modifiers) || isBridge || (isSynthetic && !isSuspend())) return false
        if (name.startsWith("access$") || name.endsWith("\$default") || name.contains("\$com_projectx")) return false
        val visibility = runCatching { kotlinFunction?.visibility }.getOrNull()
        return visibility == null || visibility == KVisibility.PUBLIC || visibility == KVisibility.PROTECTED
    }

    private fun Method.overridesInherited() = generateSequence(declaringClass.superclass) { it.superclass }
        .any { owner -> owner.declaredMethods.any { it.name == name && it.parameterTypes.contentEquals(parameterTypes) } }

    private fun Method.isSuspend() = parameterTypes.lastOrNull() == Continuation::class.java

    private fun Method.isMangled() = name.contains('-')

    private fun Method.baseName() = name.substringBefore('-')

    private fun Method.isAbstractOrOverridable() =
        Modifier.isAbstract(modifiers) || (!Modifier.isFinal(modifiers) && !Modifier.isStatic(modifiers) && !declaringClass.isFinalType())

    private fun Class<*>.isFinalType() = Modifier.isFinal(modifiers) || isEnum

    private fun Method.isDataClassMember() = COMPONENT.matches(baseName()) || baseName() == "copy"

    private fun Method.describe() = "${declaringClass.name}#${baseName()}"

    companion object {
        private val COMPONENT = Regex("component\\d+")

        // The areas of .github/scripts/api_changelog.py, less Compose: a @Composable needs the Kotlin compiler plugin,
        // so Java scripts draw with Overlay instead.
        private val PACKAGES = listOf(
            "com.projectx.script",
            "com.projectx.webwalker",
            "com.projectx.game.interfaces",
            "com.projectx.game.nxt.entity",
            "com.projectx.game.cs2",
            "com.projectx.ui.backend.dsl",
        )
        private val CLASSES = listOf(
            "com.projectx.traversal.local.LocalNav",
            "com.projectx.pathfinder.WalkMap",
            "com.projectx.pathfinder.WalkField",
            "com.projectx.traversal.MasterFarmerHat",
        )
        private val EXCLUDED = listOf("com.projectx.script.ComposePanel", "com.projectx.script.Wait")

        private val SURFACE: List<Class<*>> by lazy {
            val loader = JavaReachabilityTest::class.java.classLoader
            val names = ClassGraph().acceptPackages(*PACKAGES.toTypedArray()).acceptClasses(*CLASSES.toTypedArray())
                .enableClassInfo().scan().use { scan ->
                    scan.allClasses.filter { it.isPublic && !it.classpathElementURL.path.contains("/test/") }.map { it.name }
                }
            names.filterNot { name -> EXCLUDED.any { name.startsWith(it) } }
                .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
                .filter { it.isPublicKotlinType() }
        }

        private fun Class<*>.isPublicKotlinType(): Boolean {
            if (!Modifier.isPublic(modifiers) || isSynthetic || isAnonymousClass) return false
            val visibility = runCatching { kotlin.visibility }.getOrNull()
            if (visibility != null && visibility != KVisibility.PUBLIC) return false
            return enclosingClass?.isPublicKotlinType() ?: true
        }

        private val WAIT_FACTORIES: Set<String> =
            Wait::class.java.methods.filter { Modifier.isStatic(it.modifiers) }.map { it.name.lowercase() }.toSet()

        /** Kotlin members whose Java form has another name, keyed `class#member` and naming that form. */
        val JAVA_FORMS: Map<String, String> = mapOf(
            "com.projectx.script.Script#loop" to "com.projectx.script.JavaScript#onLoop",
            "com.projectx.script.StateMachineScript#loop" to "com.projectx.script.JavaStateMachineScript#onLoop",
            "com.projectx.script.Script#interruptWhen" to "com.projectx.script.JavaScript#shouldInterrupt",
            "com.projectx.script.State#checkNext" to "com.projectx.script.JavaState#checkNext",
            "com.projectx.script.State#checkNextState" to "com.projectx.script.JavaState#checkNext",
            "com.projectx.script.State#stateLoop" to "com.projectx.script.JavaState#onLoop",
            "com.projectx.script.api.APIKt#walkTo" to "com.projectx.script.api.APIKt#walkToTile",
            "com.projectx.script.api.APIKt#dive" to "com.projectx.script.api.APIKt#diveToTile",
            "com.projectx.script.api.TimeSpritesKt#timeSpriteElsewhere" to "com.projectx.script.api.TimeSpritesKt#timeSpriteElsewhereThan",
            "com.projectx.script.api.InstanceAnchor#getTile" to "com.projectx.script.api.InstanceAnchor#getX",
            "com.projectx.script.api.InstanceAnchor#tile" to "com.projectx.script.api.InstanceAnchor#worldX",
            "com.projectx.script.api.Area#getRandomCoordinate" to "com.projectx.script.api.Area#randomTile",
            "com.projectx.script.api.Area\$Circular#getRandomCoordinate" to "com.projectx.script.api.Area#randomTile",
            "com.projectx.script.api.Area\$Polygonal#getRandomCoordinate" to "com.projectx.script.api.Area#randomTile",
            "com.projectx.script.api.Area\$Rectangular#getRandomCoordinate" to "com.projectx.script.api.Area#randomTile",
            "com.projectx.script.api.Area#getRandomWalkableCoordinate" to "com.projectx.script.api.Area#randomWalkableTile",
            "com.projectx.script.api.Area#getCentroid" to "com.projectx.script.api.Area#centreTile",
            "com.projectx.script.api.Area#getCoordinate" to "com.projectx.script.api.Area#centreTile",
            "com.projectx.script.api.Area\$Rectangular#getBottomLeft" to "com.projectx.script.api.Area\$Rectangular#getMinX",
            "com.projectx.script.api.Area\$Rectangular#getTopRight" to "com.projectx.script.api.Area\$Rectangular#getMaxX",
            "com.projectx.script.api.Area\$Rectangular#getBottomRight" to "com.projectx.script.api.Area\$Rectangular#getMaxX",
            "com.projectx.script.api.Area\$Rectangular#getTopLeft" to "com.projectx.script.api.Area\$Rectangular#getMinX",
            "com.projectx.script.api.ActionPacer#await" to "com.projectx.script.Wait#await",
            "com.projectx.traversal.MasterFarmerHat#refreshIfUnknown" to "com.projectx.script.Wait#refreshMasterFarmerHat",
            "com.projectx.webwalker.WebWalker#walk" to "com.projectx.script.Wait#webWalk",
            "com.projectx.webwalker.WebWalker#findPath" to "com.projectx.webwalker.WebWalker#findPathAsync",
            "com.projectx.webwalker.WebPath#tile" to "com.projectx.webwalker.WebPath#getX",
            "com.projectx.traversal.local.LocalNav#getLastTeleportTile" to "com.projectx.traversal.local.LocalNav#getLastTeleportX",
            "com.projectx.game.nxt.entity.HintTrailPoint#tile" to "com.projectx.game.nxt.entity.HintTrailPoint#getTileX",
            "com.projectx.ui.backend.dsl.ImGuiDsl#window" to "com.projectx.ui.backend.dsl.Overlay#window",
            "com.projectx.ui.backend.dsl.ImGuiDsl#setNextWindowPos" to "com.projectx.ui.backend.dsl.Overlay#setNextWindowPos",
            "com.projectx.ui.backend.dsl.ImGuiDsl#setNextWindowPosUnsafe" to "com.projectx.ui.backend.dsl.Overlay#setNextWindowPosUnsafe",
            "com.projectx.ui.backend.dsl.ImGuiDsl#setNextWindowSize" to "com.projectx.ui.backend.dsl.Overlay#setNextWindowSize",
            "com.projectx.ui.backend.dsl.ImGuiDsl#setNextWindowSizeUnsafe" to "com.projectx.ui.backend.dsl.Overlay#setNextWindowSizeUnsafe",
            "com.projectx.ui.backend.dsl.scopes.LayoutScopeExtensionsKt#inputText" to "com.projectx.ui.backend.dsl.Overlay#inputText",
            "com.projectx.ui.backend.dsl.scopes.LayoutScopeExtensionsKt#inputTextMultiline" to "com.projectx.ui.backend.dsl.Overlay#inputTextMultiline",
            "com.projectx.ui.backend.dsl.scopes.LayoutScopeExtensionsKt#inputFloat2" to "com.projectx.ui.backend.dsl.Overlay#inputFloat2",
            "com.projectx.ui.backend.dsl.scopes.LayoutScopeExtensionsKt#inputFloat3" to "com.projectx.ui.backend.dsl.Overlay#inputFloat3",
            "com.projectx.ui.backend.dsl.scopes.LayoutScopeExtensionsKt#inputFloat4" to "com.projectx.ui.backend.dsl.Overlay#inputFloat4",
            "com.projectx.ui.backend.dsl.scopes.WindowScope#openPopup" to "com.projectx.ui.backend.dsl.Overlay#openPopup",
            "com.projectx.ui.backend.dsl.scopes.WindowScopeExtensionsKt#popupModal" to "com.projectx.ui.backend.dsl.Overlay#popupModal",
        )
    }
}
