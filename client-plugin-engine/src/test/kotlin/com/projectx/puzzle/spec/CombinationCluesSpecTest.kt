package com.projectx.puzzle.spec

import com.projectx.puzzle.combolock.ClueItem
import com.projectx.puzzle.combolock.CombinationClueReader
import com.projectx.puzzle.combolock.CombinationPlaqueReader
import com.projectx.puzzle.combolock.CombinationRecipeParser
import com.projectx.puzzle.combolock.CombinationRecipeReader
import com.projectx.puzzle.combolock.FremennikRiddle
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CombinationCluesSpecTest {

    private val recipeTitle = "An old recipe for something special..."

    @Test
    fun `a recipe whose amounts are all single numerals spells those numerals`() {
        val recipe = listOf(
            recipeTitle,
            "",
            " - 10 spoons of Honey",
            " - 5 pinches of Cinnamon",
            " - 1 jug of Milk",
            " - 50 grams of Oats",
        )
        assertEquals("XVIL", CombinationRecipeParser.parse(recipe))
    }

    @Test
    fun `thousands written with a comma still count as a numeral`() {
        val recipe = listOf(
            " - 1,000 grams of Flour",
            " - 500 grams of Sugar",
            " - 100 grams of Butter",
            " - 1 bowl of Egg",
        )
        assertEquals("MDCI", CombinationRecipeParser.parse(recipe))
    }

    @Test
    fun `one amount that is not a numeral switches to first letters of the ingredients`() {
        val recipe = listOf(
            recipeTitle,
            " - 10 spoons of Honey",
            " - 4 pinches of cinnamon",
            " - 1 jug of Milk",
            " - 50 grams of Oats",
        )
        assertEquals("HCMO", CombinationRecipeParser.parse(recipe))
    }

    @Test
    fun `an ingredient without a number also switches to first letters`() {
        val recipe = listOf(
            " - 100 grams of Potato",
            " - a dash of Lime",
            " - 1 cup of Ale",
            " - 5 sprigs of Yarrow",
        )
        assertEquals("PLAY", CombinationRecipeParser.parse(recipe))
    }

    @Test
    fun `the first letter is the first alphabetic character of the name`() {
        val recipe = listOf(
            " - 2 slices of 'Tomato'",
            " - 3 cups of rice",
            " - 7 leaves of Basil",
            " - 6 drops of oil",
        )
        assertEquals("TRBO", CombinationRecipeParser.parse(recipe))
    }

    @Test
    fun `the word before the name is matched without regard to case`() {
        val recipe = listOf(
            " - 1 cup OF Water",
            " - 5 cups Of Wine",
            " - 10 cups of Whey",
            " - 100 grams of Wheat",
        )
        assertEquals("IVXC", CombinationRecipeParser.parse(recipe))
    }

    @Test
    fun `only bulleted lines with an ingredient name count`() {
        val recipe = listOf(
            recipeTitle,
            "Mix the following of your choice:",
            " - 10 spoons of Honey",
            "   - 5 pinches of Cinnamon",
            " - stir well",
            " - 3 cups of ",
            " - 1 jug of Milk",
            " - 50 grams of Oats",
        )
        assertEquals("XVIL", CombinationRecipeParser.parse(recipe))
    }

    @Test
    fun `the wrong number of ingredients gives nothing`() {
        assertNull(CombinationRecipeParser.parse(emptyList()))
        assertNull(CombinationRecipeParser.parse(listOf(recipeTitle, " - 1 jug of Milk", " - 5 pinches of Salt", " - 10 cups of Tea")))
        assertNull(
            CombinationRecipeParser.parse(
                listOf(" - 1 a of Ash", " - 1 b of Bat", " - 1 c of Cod", " - 1 d of Dye", " - 1 e of Elm"),
            ),
        )
    }

    @Test
    fun `the dial count decides how many ingredients are needed`() {
        val three = listOf(" - 1 jug of Milk", " - 5 pinches of Salt", " - 10 cups of Tea")
        assertEquals("IVX", CombinationRecipeParser.parse(three, dials = 3))
        assertNull(CombinationRecipeParser.parse(three, dials = 4))
    }

    @Test
    fun `a name with no letters in it gives nothing`() {
        val recipe = listOf(" - 2 cups of Milk", " - 3 cups of 42", " - 1 cup of Tea", " - 1 cup of Ale")
        assertNull(CombinationRecipeParser.parse(recipe))
    }

    @Test
    fun `the recipe scroll is interface 220`() {
        assertEquals(220, CombinationRecipeReader.INTERFACE_ID)
    }

    @Test
    fun `every lodestone graphic resolves to the first letter of its lodestone`() {
        val lodestones = listOf(
            "Lumbridge", "Varrock", "Falador", "Ardougne", "Taverley", "Burthorpe", "Catherby",
            "Seers' Village", "Port Sarim", "Draynor Village", "Yanille", "Edgeville", "Al Kharid",
            "Bandit Camp", "Lunar Isle", "Canifis", "Eagles' Peak", "Oo'glog", "Fremennik Province",
            "Karamja", "Tirannwn", "Wilderness", "Ashdale",
        )
        lodestones.forEachIndexed { index, name ->
            assertEquals(name.first(), CombinationPlaqueReader.letterForGraphic(22233 + index), name)
            assertEquals(name.first(), CombinationPlaqueReader.letterForGraphic(22279 + index), "$name (small)")
        }
        assertEquals('P', CombinationPlaqueReader.letterForGraphic(24249))
        assertEquals('P', CombinationPlaqueReader.letterForGraphic(24250))
    }

    @Test
    fun `graphics either side of the lodestone sheets are unknown`() {
        for (graphic in listOf(-1, 0, 22232, 22256, 22278, 22302, 24248, 24251)) {
            assertNull(CombinationPlaqueReader.letterForGraphic(graphic), "graphic $graphic")
        }
    }

    @Test
    fun `the plaque is interface 140`() {
        assertEquals(140, CombinationPlaqueReader.INTERFACE_ID)
    }

    @Test
    fun `each riddle keyword gives its answer`() {
        val riddles = mapOf(
            "My first is in mage, but not in wizard." to "MIND",
            "My first is in tar, but not in a star." to "TREE",
            "My first is in the well, but not at sea." to "LIFE",
            "My first is in fish, but not in the sea." to "FIRE",
            "My first is in water, but not in the river." to "TIME",
            "My first is in wizard, but not in mage." to "WIND",
        )
        riddles.forEach { (riddle, answer) -> assertEquals(answer, FremennikRiddle.answerFrom(riddle), riddle) }
    }

    @Test
    fun `the riddle is found anywhere in a longer dialogue`() {
        val dialogue = "Peer the Seer: Solve my riddle to open the door.\n" +
            "MY FIRST IS IN THE   WELL, but not in fish.\nMy second is in water and wizard."
        assertEquals("LIFE", FremennikRiddle.answerFrom(dialogue))
    }

    @Test
    fun `only the first marker in the text counts`() {
        assertEquals("FIRE", FremennikRiddle.answerFrom("My first is in fish. My first is in mage."))
        assertNull(FremennikRiddle.answerFrom("My first is in stone. My first is in mage."))
    }

    @Test
    fun `a keyword must be a whole word`() {
        assertNull(FremennikRiddle.answerFrom("My first is in fishes, but not in the sea."))
        assertNull(FremennikRiddle.answerFrom("My first is in tarts, but not in pies."))
    }

    @Test
    fun `text without the marker has no answer`() {
        assertNull(FremennikRiddle.answerFrom(""))
        assertNull(FremennikRiddle.answerFrom("You need to answer my riddle first."))
        assertEquals("my first is in", FremennikRiddle.MARKER)
    }

    @Test
    fun `a substring channel finds the answer with or without the article`() {
        val chat = "my first is in water, but not in fire."
        assertEquals("TIME", FremennikRiddle.answerMatching { chat.contains(it) })
        val withArticle = "my first is in the well, but not in the bucket."
        assertEquals("LIFE", FremennikRiddle.answerMatching { withArticle.contains(it) })
        assertNull(FremennikRiddle.answerMatching { false })
    }

    @Test
    fun `the substring channel asks keywords in a fixed order`() {
        val probes = mutableListOf<String>()
        FremennikRiddle.answerMatching { probes += it; false }
        assertEquals(
            listOf(
                "my first is in mage", "my first is in the mage",
                "my first is in tar", "my first is in the tar",
                "my first is in well", "my first is in the well",
                "my first is in fish", "my first is in the fish",
                "my first is in water", "my first is in the water",
                "my first is in wizard", "my first is in the wizard",
            ),
            probes,
        )
        assertEquals("MIND", FremennikRiddle.answerMatching { true })
    }

    private fun item(name: String, x: Int, y: Int, amount: Int = 1) = ClueItem(name, amount, x, y)

    @Test
    fun `four coin piles read as numerals clockwise from north`() {
        val piles = listOf(
            item("Coins", 3200, 3203, amount = 1000),
            item("Coins", 3197, 3200, amount = 5),
            item("Coins", 3200, 3197, amount = 100),
            item("Coins", 3203, 3200, amount = 10),
        )
        assertEquals("MXCV", CombinationClueReader.derive(piles, 3200, 3200))
        assertEquals("MXCV", CombinationClueReader.derive(piles.reversed(), 3200, 3200))
    }

    @Test
    fun `coin piles on diagonals keep the clockwise order`() {
        val piles = listOf(
            item("coins", 102, 98, amount = 50),
            item("COINS", 98, 102, amount = 1),
            item("Coins", 102, 102, amount = 500),
            item("Coins", 98, 98, amount = 10),
        )
        assertEquals("DLXI", CombinationClueReader.derive(piles, 100, 100))
    }

    @Test
    fun `coin piles need four piles of numeral amounts`() {
        val three = listOf(item("Coins", 0, 1, 1), item("Coins", 1, 0, 5), item("Coins", 0, -1, 10))
        assertNull(CombinationClueReader.derive(three, 0, 0))
        assertNull(CombinationClueReader.derive(listOf(item("Coins", 0, 1, 100)), 0, 0))
        val badAmount = three + item("Coins", -1, 0, 20)
        assertNull(CombinationClueReader.derive(badAmount, 0, 0))
    }

    @Test
    fun `a single clean herb spells HERB`() {
        for (herb in listOf("Clean guam", "Ranarr weed", "Dwarf weed", "Clean torstol", "Spirit weed", "Fellstalk", "Clean arbuck")) {
            assertEquals("HERB", CombinationClueReader.derive(listOf(item(herb, 5, 5)), 0, 0), herb)
        }
    }

    @Test
    fun `a grimy herb is not a clean herb`() {
        assertEquals("GUAM", CombinationClueReader.derive(listOf(item("Grimy guam", 5, 5)), 0, 0))
        assertNull(CombinationClueReader.derive(listOf(item("Grimy ranarr", 5, 5)), 0, 0))
    }

    @Test
    fun `a single item spells the four letter word in its name`() {
        assertEquals("ROPE", CombinationClueReader.derive(listOf(item("Rope", 1, 1)), 0, 0))
        assertEquals("BOOT", CombinationClueReader.derive(listOf(item("Leather boot", 1, 1)), 0, 0))
        assertEquals("CAKE", CombinationClueReader.derive(listOf(item("Chocolate cake", 1, 1)), 0, 0))
        assertEquals("LAMP", CombinationClueReader.derive(listOf(item("Oil lamp (empty)", 1, 1)), 0, 0))
        assertEquals("BEAD", CombinationClueReader.derive(listOf(item("Red bead", 1, 1)), 0, 0))
    }

    @Test
    fun `a single long word gives its last four letters`() {
        assertEquals("FISH", CombinationClueReader.derive(listOf(item("Monkfish", 1, 1)), 0, 0))
        assertEquals("BALL", CombinationClueReader.derive(listOf(item("Snowball", 1, 1)), 0, 0))
    }

    @Test
    fun `a single item with no usable word gives nothing`() {
        assertNull(CombinationClueReader.derive(listOf(item("Raw shrimps", 1, 1)), 0, 0))
        assertNull(CombinationClueReader.derive(listOf(item("Ash", 1, 1)), 0, 0))
    }

    @Test
    fun `four items spell their initials from farthest to nearest`() {
        val items = listOf(
            item("Egg", 1, 0),
            item("Hammer", 0, 4),
            item("Onion", -2, -2),
            item("Pot", 0, -3),
        )
        assertEquals("HPOE", CombinationClueReader.derive(items, 0, 0))
        assertEquals("HPOE", CombinationClueReader.derive(items.shuffled(Random(7)), 0, 0))
    }

    @Test
    fun `items at the same distance keep the order they were given`() {
        val items = listOf(item("Bucket", 2, 0), item("Apple", 0, 2), item("Tinderbox", -2, 0), item("Knife", 0, -2))
        assertEquals("BATK", CombinationClueReader.derive(items, 0, 0))
    }

    @Test
    fun `four items that include coins still use initials`() {
        val items = listOf(item("Coins", 0, 5, 3), item("Axe", 0, 3), item("Wine", 0, 2), item("Net", 0, 1))
        assertEquals("CAWN", CombinationClueReader.derive(items, 0, 0))
    }

    @Test
    fun `any other count of items gives nothing`() {
        assertNull(CombinationClueReader.derive(emptyList(), 0, 0))
        assertNull(CombinationClueReader.derive(listOf(item("Axe", 0, 1), item("Net", 0, 2)), 0, 0))
        assertNull(CombinationClueReader.derive(listOf(item("Axe", 0, 1), item("Net", 0, 2), item("Pie", 0, 3)), 0, 0))
        val five = listOf("Axe", "Net", "Pie", "Egg", "Jug").mapIndexed { i, n -> item(n, 0, i + 1) }
        assertNull(CombinationClueReader.derive(five, 0, 0))
    }

    @Test
    fun `clue items are value objects`() {
        val item = ClueItem("Coins", 50, 3200, 3201)
        assertEquals(ClueItem("Coins", 50, 3200, 3201), item)
        val (name, amount, x, y) = item
        assertEquals(listOf<Any>("Coins", 50, 3200, 3201), listOf(name, amount, x, y))
        assertEquals(ClueItem("Coins", 100, 3200, 3201), item.copy(amount = 100))
    }
}
