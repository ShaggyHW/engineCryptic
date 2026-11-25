package com.projectx.quest.spec

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.projectx.core.ProjectPaths
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestLibraryStorageTest {
    private val bundleDir = File(requireNotNull(ProjectPaths.root), "client-plugin-engine/src/main/resources/quest-data/quests")

    private fun withRoot(block: (File) -> Unit) {
        val root = IsolatedLibrary.newRoot()
        try {
            block(root)
        } finally {
            IsolatedLibrary.discard(root)
        }
    }

    private fun write(dir: File, name: String, json: String) {
        dir.mkdirs()
        File(dir, name).writeText(json.trimIndent())
    }

    private fun read(file: File): JsonObject = file.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }

    @Test
    fun `bundled guides load, save and reload unchanged`() = withRoot { root ->
        bundleDir.copyRecursively(IsolatedLibrary.questDir(root))
        IsolatedLibrary.run("bundledGuidesSurviveSaving", root)
        val saved = read(File(IsolatedLibrary.questDir(root), "cooks-assistant.json"))
        assertEquals("cooks-assistant", saved.get("slug").asString)
        assertTrue(saved.getAsJsonArray("steps").size() > 0)
    }

    @Test
    fun `saving a quest writes its own file and updates lookups`() = withRoot { root ->
        IsolatedLibrary.questDir(root).mkdirs()
        File(bundleDir, "cooks-assistant.json").copyTo(File(IsolatedLibrary.questDir(root), "cooks-assistant.json"))
        IsolatedLibrary.run("saveWritesOneFileAndRefreshesLookups", root)
    }

    @Test
    fun `a quest using every kind survives a save and reload`() = withRoot { root ->
        IsolatedLibrary.questDir(root).mkdirs()
        IsolatedLibrary.run("everyKindRoundTrips", root)
    }

    @Test
    fun `saved files use the documented shape`() = withRoot { root ->
        IsolatedLibrary.questDir(root).mkdirs()
        IsolatedLibrary.run("everyKindRoundTrips", root)
        val file = File(IsolatedLibrary.questDir(root), "spec-every-kind.json")
        val text = file.readText()
        assertTrue(text.startsWith("{\n  \"slug\": \"spec-every-kind\""), "pretty printed with two-space indent: ${text.take(60)}")

        val q = read(file)
        assertEquals(
            listOf("slug", "name", "members", "length", "releaseDate", "neededItems", "recommendedItems", "combatNPCs", "prereqQuests", "questReqs", "steps", "stageVarbit", "stageVarbitCompleteValue", "cacheQuestId"),
            q.keySet().toList(),
            "null fields are left out, the rest keep declaration order",
        )
        assertEquals(listOf("type", "name", "level", "ironmanOnly"), q.getAsJsonArray("questReqs")[0].asJsonObject.keySet().toList())
        assertEquals(listOf("type", "level", "text", "ironmanOnly"), q.getAsJsonArray("questReqs")[1].asJsonObject.keySet().toList())
        assertEquals(listOf("name", "itemId", "quantity", "duringQuest"), q.getAsJsonArray("neededItems")[0].asJsonObject.keySet().toList())
        assertEquals(listOf("name", "level", "quantity"), q.getAsJsonArray("combatNPCs")[0].asJsonObject.keySet().toList())

        val steps = q.getAsJsonArray("steps")
        val full = steps[0].asJsonObject
        assertEquals(
            listOf("title", "text", "warning", "tpHint", "actions", "postconditions", "jumpconditions", "jumpOffset", "neededItems", "recommendedItems", "solverId"),
            full.keySet().toList(),
        )
        assertEquals(listOf("type", "hover", "url"), full.getAsJsonObject("tpHint").keySet().toList())
        assertEquals(
            listOf("actions", "postconditions", "jumpconditions", "jumpOffset", "neededItems", "recommendedItems"),
            steps[1].asJsonObject.keySet().toList(),
            "an empty step keeps its lists and offset but drops null text fields",
        )

        val actions = full.getAsJsonArray("actions").map { it.asJsonObject }
        assertEquals(
            listOf(
                "direction", "direction", "direction", "modelHighlight", "modelHighlight", "conversationHighlight", "inventoryHighlight",
                "inventoryHighlight", "continueConversation", "resetInstance", "interfaceComponentHighlight", "interfaceComponentHighlight",
                "pathGuide", "pathGuide", "textHint", "unknown",
            ),
            actions.map { it.get("t").asString },
        )
        assertEquals(listOf("t", "x", "heightFine", "y", "instance"), actions[0].keySet().toList(), "direction always writes instance")
        assertFalse(actions[0].get("instance").asBoolean)
        assertEquals(listOf("t", "x", "heightFine", "y", "instance", "tile", "distance"), actions[1].keySet().toList())
        assertEquals(
            listOf("t", "kind", "typeId", "modelIds", "candidateNpcTypeIds", "candidateObjectTypeIds", "displayName", "priority", "instance", "distance", "atLocation"),
            actions[3].keySet().toList(),
        )
        assertEquals(listOf("x", "heightFine", "y"), actions[3].getAsJsonObject("atLocation").keySet().toList())
        assertEquals(listOf("t", "kind", "modelIds", "displayName"), actions[4].keySet().toList(), "unset id, priority, distance and flags are omitted")
        assertEquals(listOf("t", "text"), actions[5].keySet().toList())
        assertEquals(listOf("t", "itemId", "modelIds", "candidateItemTypeIds", "displayName"), actions[6].keySet().toList())
        assertEquals(listOf("t", "itemId", "modelIds", "displayName"), actions[7].keySet().toList())
        assertEquals(-1, actions[7].get("itemId").asInt)
        assertEquals(listOf("t"), actions[8].keySet().toList())
        assertEquals(listOf("t", "interfaceId", "componentId", "slotId", "label", "color"), actions[10].keySet().toList())
        assertEquals(0x11223344, actions[10].get("color").asInt)
        assertEquals(listOf("t", "interfaceId", "componentId"), actions[11].keySet().toList())
        assertEquals(listOf("t", "waypoints"), actions[12].keySet().toList(), "a world-space path omits instance")
        assertEquals(listOf("x", "heightFine", "y"), (actions[12].get("waypoints") as JsonArray)[0].asJsonObject.keySet().toList())
        assertEquals(listOf("t", "waypoints", "instance"), actions[13].keySet().toList())
        assertEquals(listOf("t", "name"), actions[15].keySet().toList())
        assertEquals("mystery", actions[15].get("name").asString)

        val post = full.getAsJsonArray("postconditions").map { it.asJsonObject }
        assertEquals(
            listOf(
                "distanceTo", "distanceFrom", "distanceToWithHeight", "distanceFromWithHeight", "notInInstance", "changedInstance", "manual",
                "always", "captureConversationState", "stateEquals", "inventoryContains", "inventoryContains", "inventoryDoesNotContain",
                "modelVisible", "modelVisible", "modelNotVisible", "questStarted", "questComplete", "questInterfaceOpen", "interfaceOpen",
                "interfaceOpen", "npcNearTile", "npcNearTile",
            ),
            post.map { it.get("t").asString },
        )
        assertEquals(listOf("t", "x", "heightFine", "y", "range"), post[0].keySet().toList(), "instance only when true")
        assertEquals(listOf("t", "x", "heightFine", "y", "range", "instance"), post[1].keySet().toList())
        assertEquals(listOf("t", "pattern", "key"), post[8].keySet().toList())
        assertEquals(listOf("t", "key", "value"), post[9].keySet().toList())
        assertEquals(listOf("t", "itemId", "displayName", "quantity", "candidateItemTypeIds"), post[10].keySet().toList())
        assertEquals(listOf("t", "itemId", "displayName", "quantity"), post[11].keySet().toList())
        assertEquals(listOf("t", "itemId", "displayName", "candidateItemTypeIds"), post[12].keySet().toList())
        assertEquals(
            listOf("t", "kind", "typeId", "modelIds", "candidateNpcTypeIds", "candidateObjectTypeIds", "displayName", "instance", "atLocation", "animated", "quantity"),
            post[13].keySet().toList(),
        )
        assertEquals(listOf("t", "kind", "modelIds", "displayName"), post[14].keySet().toList())
        assertEquals(listOf("t", "kind", "typeId", "modelIds", "displayName", "instance", "atLocation"), post[15].keySet().toList())
        assertEquals(listOf("t", "interfaceId"), post[19].keySet().toList())
        assertEquals(-1, post[20].get("interfaceId").asInt, "an unset interface id is still written")
        assertEquals(listOf("t", "typeId", "displayName", "tileX", "tileY", "plane", "distance", "instance", "candidateNpcTypeIds"), post[21].keySet().toList())
        assertEquals(listOf("t", "typeId", "displayName", "tileX", "tileY", "plane", "distance"), post[22].keySet().toList())

        val jump = full.getAsJsonArray("jumpconditions").map { it.asJsonObject }
        assertEquals(
            listOf("conversationActive", "conversationInactive", "conversationText", "chatText", "inInstance", "inCombatWith", "itemClicked", "generic", "unknown"),
            jump.map { it.get("t").asString },
        )
        assertEquals(listOf("t", "npcId", "displayName"), jump[5].keySet().toList())
        assertEquals(listOf("t", "itemId"), jump[6].keySet().toList())
        assertEquals(-2, full.get("jumpOffset").asInt)
    }

    @Test
    fun `hand-written files decode with legacy keys and defaults`() = withRoot { root ->
        write(
            IsolatedLibrary.questDir(root), "anything.json",
            """
            {
              "slug": "legacy-quest",
              "name": "Legacy Quest",
              "members": false,
              "releaseDate": 0,
              "neededItems": [], "recommendedItems": [], "combatNPCs": [], "prereqQuests": [], "questReqs": [],
              "stageVarbit": 42,
              "stageVarbitCompleteValue": 5,
              "cacheQuestId": -1,
              "someFieldNobodyKnows": true,
              "steps": [
                {
                  "text": "only text",
                  "actions": [
                    {"t": "direction", "x": 10, "heightFine": 0, "y": -20},
                    {"t": "direction", "x": 3200, "heightFine": 0, "y": 3200},
                    {"t": "direction", "x": 10, "heightFine": 0, "y": 20, "instance": false, "tile": true, "distance": 3},
                    {"t": "modelHighlight", "kind": "npc", "id": 77, "displayName": "Legacy id"},
                    {"t": "modelHighlight", "kind": "object", "modelIds": [5, 6], "displayName": "Located", "atLocation": {"x": 1, "y": 2}},
                    {"t": "pathGuide", "waypoints": [{"x": 1, "heightFine": 0, "y": 2}, {"x": 3, "heightFine": 7, "y": 4}]},
                    {"t": "pathGuide", "waypoints": []},
                    {"t": "pathGuide", "instance": true},
                    {"t": "interfaceComponentHighlight", "interfaceId": 1477, "componentId": 9},
                    {"t": "textHint", "text": "press the thing"},
                    {"t": "unknown", "name": "someOldName"},
                    {"t": "teleportBubble", "where": "nowhere"}
                  ],
                  "postconditions": [
                    {"t": "distanceToWithHeight", "x": 1, "heightFine": 800, "y": 2, "plane": 2},
                    {"t": "distanceToWithHeight", "x": 1, "heightFine": 0, "y": 2},
                    {"t": "distanceTo", "x": 5, "heightFine": 0, "y": 6, "range": 3, "instance": null},
                    {"t": "interfaceOpen"},
                    {"t": "npcNearTile"},
                    {"t": "inCombatWith", "npcId": 9},
                    {"t": "modelVisible", "kind": "npc", "id": 12, "displayName": "Old id"},
                    {"t": "captureConversationState", "pattern": "(\\d+)", "key": "count"},
                    {"t": "stateEquals", "key": "count", "value": "3"},
                    {"t": "generic"},
                    {"t": "unknown", "name": "legacyThing"},
                    {"t": "brandNewKind"}
                  ]
                }
              ]
            }
            """,
        )
        IsolatedLibrary.run("handWrittenFilesDecodeWithDefaults", root)
    }

    @Test
    fun `per-quest files win over the legacy bundle by slug`() = withRoot { root ->
        IsolatedLibrary.legacyBundle(root).apply { parentFile.mkdirs() }.writeText(
            """
            {"quests": [
              {"slug": "bundle-only", "name": "Bundle Only", "steps": []},
              {"slug": "shared", "name": "From Bundle", "steps": []}
            ]}
            """.trimIndent(),
        )
        val dir = IsolatedLibrary.questDir(root)
        write(dir, "shared.json", """{"slug": "shared", "name": "From File", "steps": []}""")
        write(dir, "only-file.json", """{"slug": "only-file", "name": "Only File", "steps": []}""")
        IsolatedLibrary.run("legacyBundleIsOverriddenByPerQuestFiles", root)
    }

    @Test
    fun `unreadable files are skipped without losing the rest`() = withRoot { root ->
        val dir = IsolatedLibrary.questDir(root)
        write(dir, "good.json", """{"slug": "good", "name": "Good", "steps": []}""")
        write(dir, "broken.json", """{ "slug": "broken", """)
        write(dir, "missing-field.json", """{"slug": "bad", "name": "Bad", "steps": [{"actions": [{"t": "direction", "y": 1, "heightFine": 0}]}]}""")
        write(dir, "notes.txt", "not a quest")
        File(dir, "folder.json").mkdirs()
        IsolatedLibrary.run("unreadableFilesAreSkipped", root)
    }

    @Test
    fun `without a quest directory the classpath copy is used until one exists`() = withRoot { root ->
        assumeFalse(IsolatedLibrary.homeQuestDir.exists(), "a home quest directory would be chosen instead")
        IsolatedLibrary.run("missingDirectoryFallsBackToClasspathUntilCreated", root)
    }

    @Test
    fun `an empty quest directory yields no quests`() = withRoot { root ->
        IsolatedLibrary.questDir(root).mkdirs()
        IsolatedLibrary.run("emptyDirectoryLoadsNothing", root)
        assertNull(File(IsolatedLibrary.questDir(root), "cooks-assistant.json").takeIf { it.exists() })
    }
}
