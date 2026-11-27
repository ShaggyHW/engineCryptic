package com.projectx.javaapi;

import com.projectx.game.chat.MessageType;
import com.projectx.game.input.Key;
import com.projectx.game.interfaces.Bank;
import com.projectx.game.items.Item;
import com.projectx.game.nxt.entity.Bearings;
import com.projectx.game.nxt.entity.GroundItem;
import com.projectx.game.nxt.entity.HintIcon;
import com.projectx.game.nxt.entity.location.SceneObject;
import com.projectx.game.nxt.entity.player.Player;
import com.projectx.pathfinder.RouteFinderKt;
import com.projectx.pathfinder.WalkField;
import com.projectx.pathfinder.WorldCollision;
import world.gregs.voidps.path.Route;
import world.gregs.voidps.path.RouteKt;
import com.projectx.script.BooleanConfigItem;
import com.projectx.script.ConfigurableScript;
import com.projectx.script.IntConfigItem;
import com.projectx.script.JavaScript;
import com.projectx.script.JavaState;
import com.projectx.script.JavaStateMachineScript;
import com.projectx.script.MultiSelectConfigItem;
import com.projectx.script.ScriptDescription;
import com.projectx.script.Wait;
import com.projectx.script.api.ActionPacer;
import com.projectx.script.api.Area;
import com.projectx.script.api.BankSite;
import com.projectx.script.api.Banks;
import com.projectx.script.api.DangerZone;
import com.projectx.script.api.Equipment;
import com.projectx.script.api.Familiar;
import com.projectx.script.api.GroupTeleports;
import com.projectx.script.api.Lodestone;
import com.projectx.script.api.MakeX;
import com.projectx.script.api.Prayer;
import com.projectx.script.api.PuzzleBoxKt;
import com.projectx.script.api.TeleportSpell;
import com.projectx.script.api.TileArea;
import com.projectx.script.event.impl.XPDrop;
import com.projectx.traversal.local.LocalNav;
import com.projectx.ui.backend.dsl.ImGuiState;
import com.projectx.ui.backend.dsl.utils.ImGuiColors;
import com.projectx.ui.backend.dsl.utils.ImGuiStyleVar;
import com.projectx.webwalker.WebWalker;
import org.projectx.core.game.combat.Ability;
import org.projectx.core.game.combat.Effect;
import org.projectx.core.game.skill.Skill;
import world.gregs.voidps.type.Tile;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.projectx.script.api.APIKt.*;
import static com.projectx.script.api.LootKt.*;
import static com.projectx.script.api.MagicNotepaperKt.*;
import static com.projectx.script.api.NpcScanKt.*;
import static com.projectx.ui.backend.dsl.Overlay.*;

/**
 * Compiled with the engine's tests and never run against the game: if any of this stops compiling, part of the API
 * has stopped being reachable from Java. Written the way a script developer would write it.
 */
@ScriptDescription(name = "Java API parity", version = "1.0.0", author = "Project X", description = "Compile check", visible = false)
public class JavaApiParityScript extends JavaScript implements ConfigurableScript {

    private final BooleanConfigItem bank = new BooleanConfigItem("Bank", "Bank instead of dropping");
    private final IntConfigItem range = new IntConfigItem("Range", "Search range", 20);
    private final MultiSelectConfigItem targets = new MultiSelectConfigItem("Targets", "What to attack")
        .withScan("Scan nearby", () -> List.of("Guard", "White knight"));
    private boolean castWorked;

    @Override
    public Wait onLoop() {
        // Tile-taking helpers, in coordinate form.
        SceneObject rock = findClosestObjectToTile(3200, 3200, 0, "Rocks");
        SceneObject tree = findClosestObjectToTile(3200, 3200, 0, range.getValue(), false, o -> o.hasOption("Chop down"));
        List<SceneObject> nearby = getAllObjectsWithinRange(3200, 3200, 0, 15);
        boolean clicked = interactClosestReachableObjectToTile(3200, 3200, 0, "Mine");
        walkToTile(3200, 3200, 0);
        diveToTile(3200, 3200, 0);
        if (rock != null && tree != null) System.out.println(rock.getPlane() + nearby.size() + (clicked ? 1 : 0));

        Tile safe = calculateClosestSafeTile(3200, 3200, 0, List.of(new DangerZone(3201, 3201, 0, 1)));
        if (safe != null) System.out.println(safe.getX() + "," + safe.getY());
        boolean clear = bresenhamLos(3200, 3200, 3205, 3205, List.of(new TileArea(3202, 3202, 0, 1, 1)));
        boolean inside = new Area.Rectangular(3190, 3190, 3210, 3210, 0).contains(3200, 3200, 0)
            && new Area.Circular(3200, 3200, 0, 5.0).contains(3201, 3201, 0)
            && new Area.Polygonal(new int[]{3190, 3210, 3200}, new int[]{3190, 3190, 3210}, 0).contains(3200, 3195, 0);
        Tile somewhere = new Area.Rectangular(3190, 3190, 3210, 3210, 0).randomWalkableTile();
        if (somewhere != null) walkToTile(somewhere.getX(), somewhere.getY(), somewhere.getLevel());
        int lodestoneX = Lodestone.VARROCK.getTileX() + Lodestone.VARROCK.getPlane();
        if (clear && inside && lodestoneX > 0 && bank.getValue() && targets.isSelected("Guard")) return Wait.ms(100);

        // Companion members are static; named objects keep INSTANCE, which compiled scripts already link against.
        boolean makeXOpen = MakeX.INSTANCE.isOpen();
        Bank.doBankAction(Bank.getCLOSE_COMPONENT_ID());
        Equipment.Slot.getItem(Equipment.Slot.HEAD);
        int white = ImGuiColors.INSTANCE.getWHITE();
        WebWalker.findPathAsync(3200, 3200, 3210, 3210, 0);
        if (makeXOpen && white != 0) return Wait.abort();

        // Members built on Tile, in forms Java can call.
        Player me = getLocalPlayer();
        Tile serverTile = me.getServerTile();
        boolean facing = me.isFacing(3205, 3205, 45.0) || me.isFacingAway(3205, 3205, 45.0);
        int local = me.getLocalTileX() + me.getLocalTileY();
        int bearing = Bearings.between(3200, 3200, 3205, 3205);
        for (HintIcon hint : HintIcon.all()) local += hint.getTileX() + hint.getTileY() + hint.getPlane();
        List<BankSite> banks = Banks.byDistanceFrom(3200, 3200, 0);
        if (!banks.isEmpty()) local += banks.get(0).getTileX() + banks.get(0).getTileY() + banks.get(0).getPlane();
        Tile varrock = TeleportSpell.VARROCK.getDestination();
        Area.Rectangular box = new Area.Rectangular(3190, 3190, 3210, 3210, 0);
        local += box.getMinX() + box.getMaxY() + box.getPlane() + box.getCoordinatesInArea(0).size();
        WalkField field = LocalNav.INSTANCE.field(3200, 3200, 0);
        if (field != null) local += field.getStartX() + field.getStartY() + field.getStartPlane();
        if (serverTile != null && varrock != null && facing && bearing + local > 0) return Wait.ms(100);

        // Scanning, looting and noting.
        List<String> attackable = attackableNpcNamesNearby();
        List<GroundItem> bones = wantedGroundItems(3200, 3200, 0, 10, name -> name.equals("Bones"));
        Item hide = getInventory().getItem("Cowhide");
        if (hide != null && getCanBeNoted(hide) && hasRoomFor(hide.getId(), false)) {
            return Wait.sequence(
                () -> Wait.noteWithMagicNotepaper(hide, noted -> {}),
                () -> Wait.takeLootOnce(3200, 3200, 0, 10, false, name -> name.equals("Bones"), took -> {}),
                () -> Wait.takeLootOnce(3200, 3200, 0, 10, true, name -> true)
            );
        }
        if (attackable.size() + bones.size() + attackableNpcNamesNearby(15).size() > 0 && MAGIC_NOTEPAPER.isEmpty()) return Wait.ms(100);

        // Routes and collision in coordinate form.
        if (rock != null) {
            Route toRock = RouteFinderKt.routeToObject(3200, 3200, 0, rock);
            Tile end = RouteFinderKt.routedDestination(3200, 3200, 0, rock);
            if (toRock.getSuccess() && end != null) local += RouteKt.toTiles(toRock, 0).size();
        }
        local += RouteFinderKt.routeToTile(3200, 3200, 0, 3210, 3210).getDistance();
        boolean sight = RouteFinderKt.hasLineOfSight(3200, 3200, 0, 1, 3205, 3205, 1);
        Tile base = WorldCollision.INSTANCE.getSceneBase();
        if (sight && base != null) return Wait.interruptWhen(() -> me.isMoving(), Wait.xpDrop(), () -> castWorked = false);

        // Kotlin waits under their Kotlin names, as a Kotlin script is converted line by line.
        if (castWorked) {
            return Wait.sequence(
                () -> Wait.delay(600, 150),
                () -> Wait.delayTicks(2.0, 120),
                () -> Wait.delayBetween(200, 400),
                () -> Wait.delayUntil(3000, () -> getInventory().isFull()),
                () -> Wait.delayWhile(3000, () -> me.isMoving()),
                () -> Wait.waitThenDelayUntil(600, 3000, () -> !me.isAniMoving()),
                () -> Wait.waitThenDelayWhile(600, 3000, me::isMoving),
                () -> Wait.waitForXPDrop(Skill.MINING),
                () -> Wait.waitForEvent(5000, event -> event instanceof XPDrop),
                () -> Wait.waitForChatContaining(MessageType.PUBLIC_CHAT, "hello"),
                () -> Wait.waitUntilIdle(10, 2),
                () -> Wait.waitUntilStoppedMoving(10, 2),
                () -> Wait.waitUntilNotMoving(),
                () -> Wait.waitUntilNotAniMoving(5000),
                () -> Wait.geReaction(),
                () -> Wait.await(ActionPacer.ACTIONS),
                () -> Wait.webWalkTo(3210, 3210, 0),
                () -> Wait.findWebPath(3210, 3210, 0, result -> System.out.println(result.isSuccess())),
                () -> Wait.canWebWalkTo(3210, 3210, 0, reachable -> {}),
                () -> Wait.findWebPathToTiles(new int[]{3210}, new int[]{3210}, 0, result -> {}),
                () -> Wait.canWebWalkToArea(box, reachable -> {}),
                () -> Wait.findNearestBank(site -> System.out.println(site == null ? "none" : site.getName())),
                () -> Wait.lodestoneTeleport(Lodestone.VARROCK, result -> {}),
                () -> Wait.dive(3205, 3205, 0, Ability.BLADED_DIVE),
                () -> Wait.diveThenSurge(3205, 3205, 0, Ability.BLADED_DIVE, hops -> {}),
                () -> Wait.surgeThenDive(3205, 3205, 0, Ability.BLADED_DIVE),
                () -> Wait.refreshMasterFarmerHat(count -> {}),
                () -> PuzzleBoxKt.getPuzzleBoxSolved() ? Wait.closePuzzleBox() : Wait.solvePuzzleBox(() -> me.isMoving(), ok -> {}),
                () -> PuzzleBoxKt.clickPuzzleBoxClose() ? Wait.ms(300) : null
            );
        }

        // Every Kotlin helper that waits, as a Wait.
        return Wait.sequence(
            () -> Wait.castAndWaitForCd(Ability.SURGE, ok -> castWorked = ok),
            () -> Wait.castWithAdren(Ability.SURGE, 50),
            () -> Wait.castIf(castWorked, Ability.SURGE, () -> !castWorked),
            () -> Wait.smartCast(Ability.SURGE, ok -> {}, 0, () -> true, null, 6200, true),
            () -> Wait.castWithEffectStacks(Ability.SURGE, Effect.OVERLOADED, 2),
            () -> Wait.togglePrayer(Prayer.SORROW, true),
            () -> Wait.toggleQuickPrayers(false),
            () -> Wait.clickKey(Key.RETURN),
            () -> Wait.clickKey('1'),
            () -> Wait.findAndPickupItems("Bones", "Coins"),
            () -> Wait.checkPorter(),
            () -> Wait.useLodestone(Lodestone.VARROCK),
            () -> Wait.teleportWithGroupSystem(GroupTeleports.CROESUS),
            () -> Wait.randomizedWorldHop(),
            () -> Wait.randomizedWorldHopQuick(1),
            () -> Wait.checkWorldPop(hopped -> {}),
            () -> Wait.makeX(name -> name.contains("bar")),
            () -> Wait.makeXSelect(name -> name.contains("bar"), category -> true, ok -> {}),
            () -> Wait.makeXConfirm(),
            () -> Wait.selectMakeCategory(name -> true),
            () -> Wait.smithSetQuantity(10, quantity -> {}),
            () -> Wait.smithSelectTier(2),
            () -> Wait.smithSelectItem(name -> true),
            () -> Wait.smithMake(),
            () -> Wait.familiarSummonFamiliar(Familiar.SPIRIT_WOLF),
            () -> Wait.familiarRenewFromBank(Familiar.SPIRIT_WOLF, ok -> {}),
            () -> Wait.familiarCastSpecial("Rock"),
            () -> Wait.bobStoreByName(Map.of("Iron ore", 28)),
            () -> Wait.bobWithdrawById(Map.of(440, 28), ok -> {}),
            () -> Wait.bobGiveAllItems(),
            () -> Wait.startOrRejoinInstance(ok -> {}),
            () -> Wait.joinInstance(),
            () -> Wait.event(event -> event instanceof XPDrop, 5000),
            () -> Wait.chatContaining(MessageType.PUBLIC_CHAT, "hello"),
            () -> Wait.untilNotMoving(),
            () -> Wait.pauseOthersFor(1000),
            () -> Wait.webWalk(3210, 3210, 0, 2, result -> System.out.println(result.isSuccess())),
            () -> Wait.xpDrop(Skill.MINING)
        );
    }

    @Override
    protected boolean shouldInterrupt() {
        return false;
    }

    @Override
    public void render() {
        ImGuiState<Boolean> showMore = persistentState("parity.more", () -> boolState(false));
        setNextWindowSize(300f, 200f, COND_FIRST_USE_EVER);
        window("Java API parity", WINDOW_NO_COLLAPSE | WINDOW_ALWAYS_AUTO_RESIZE, w -> {
            section(w, "Status");
            text(w, "Running");
            xpProgressBar(w, Skill.MINING);
            button(w, "Stop", this::stop);
            checkbox(w, "Show more", showMore);
            combo(w, "Mode", 0, List.of("A", "B"), index -> {});
            properties(w, "stats", table -> {
                valueRow(table, "Ores", "12");
                row(table, "Range", cell -> inputInt(cell, "##range", range.getValue(), range::setValue));
            });
            tabBar(w, "tabs", tabs -> tabItem(tabs, "Main", tab -> text(tab, "Main tab")));
            collapsingHeader(w, "Details", details -> text(details, "More"));
            inputText(w, "Name", "", 32, INPUT_CHARS_NO_BLANK, value -> {});
            inputTextMultiline(w, "Notes", "", 0f, 80f, value -> {});
            inputFloat2(w, "Offset", floatState(0f), floatState(0f), "%.1f");
            if (w.getClass() != null) openPopup(w, "confirm");
            popup(w, "confirm", popupScope -> {
                text(popupScope, "Sure?");
                popupScope.closeCurrentPopup();
            });
            popupModal(w, "modal", WINDOW_NO_RESIZE, modal -> text(modal, "Modal"));
        });
        window("Closable", 0, showMore, w -> text(w, "Close me"));
        ImGuiState<Boolean> confirmOpen = persistentState("parity.confirm", () -> boolState(false));
        ImGuiState<Float> red = persistentState("parity.red", () -> floatState(1f));
        ImGuiState<Float> alpha = persistentState("parity.alpha", () -> floatState(1f));
        ImGuiState<Integer> low = persistentState("parity.low", () -> intState(1));
        ImGuiState<Integer> high = persistentState("parity.high", () -> intState(99));
        var icon = graphicTexture(13198);
        var fromFile = textureFromPath("icon.png");
        ImGuiState<Set<Integer>> picked = persistentState("parity.picked", () -> indexSetState(Set.of(0)));
        window("More controls", WINDOW_MENU_BAR, w -> {
            menuBar(w, bar -> {
                menu(bar, "File", file -> {
                    menuItem(file, "Save", () -> {});
                    menuItem(file, "Quit", "Ctrl+Q", () -> {});
                    menuItem(file, "Pinned", "", true, () -> {});
                    menu(file, "Recent", recent -> menuItem(recent, "One", () -> {}));
                });
            });
            menu(w, "Disabled", false, disabled -> {});
            menuItem(w, "Bare", () -> {});
            button(w, "Item", () -> {});
            contextMenuItem(w, ctx -> {
                menuItem(ctx, "Copy", () -> {});
                menu(ctx, "More", more -> menuItem(more, "Paste", "Ctrl+V", false, () -> {}));
                whileShown(ctx, () -> {});
                ctx.closeCurrentPopup();
            });
            contextMenuItem(w, "item-ctx", MOUSE_RIGHT, ctx -> {});
            contextMenuWindow(w, ctx -> menuItem(ctx, "Reset", () -> {}));
            contextMenuWindow(w, "win-ctx", MOUSE_RIGHT, false, ctx -> {});
            contextMenuVoid(w, "void-ctx", ctx -> {});
            confirmationDialog(w, "Sure?", "Really stop?", confirmOpen);
            confirmationDialog(w, "Sure 2?", "Really stop?", confirmOpen, this::stop, () -> {});
            inputDialog(w, "Rename", "Name", stringState(""), confirmOpen, name -> {});
            choiceDialog(w, "Pick", "Which?", confirmOpen, List.of("A", "B"), (index, choice) -> {});
            tooltip(w, "Tip");
            setTooltip(w, "Tip");
            colorEdit3(w, "Colour", red, red, red);
            colorEdit4(w, "Colour A", red, red, red, alpha, COLOR_ALPHA_BAR | COLOR_NO_INPUTS);
            colorPicker4(w, "Picker", red, red, red, alpha, COLOR_NO_SIDE_PREVIEW);
            dragFloat(w, "Speed", red);
            dragFloat(w, "Speed 2", red, 0.1f, 0f, 10f, "%.1f", SLIDER_ALWAYS_CLAMP);
            dragInt(w, "Count", low, 1f, 0, 100);
            rangeSliderFloat(w, "Range f", red, alpha, 0f, 1f);
            rangeSliderInt(w, "Range i", low, high, 1, 99, "%d", SLIDER_NO_INPUT);
            arrowButton(w, "##up", DIR_UP, "^", () -> {});
            if (fromFile != null) image(w, fromFile, 16f, 16f);
            if (icon != null) {
                iconButton(w, "##icon", icon, 16f, () -> {});
                iconButton(w, "##icon2", icon, 16f, 28f, ImGuiColors.INSTANCE.getWHITE(), () -> {});
                imageButton(w, icon, 32f, 32f, () -> {});
                imageButton(w, icon, 32f, 32f, 0f, 0f, 0.5f, 0.5f, () -> {});
            }
            checkboxGrid(w, "flags", List.of(Map.entry("Bank", showMore), Map.entry("Drop", confirmOpen)));
            checkboxGrid(w, "flags3", List.of(Map.entry("Bank", showMore)), 3);
            multiSelectListBox(w, "Targets", List.of("Guard", "Knight"), picked);
            multiSelectListBox(w, "Targets 2", List.of("Guard", "Knight"), picked, 200f, 80f);
            indent(w);
            indent(w, 12f);
            unindent(w, 12f);
            unindent(w);
            columns(w, 2, "cols", false);
            text(w, "Left");
            nextColumn(w);
            setColumnWidth(w, 0, 120f);
            columns(w);
            table(w, "t", 2, t -> {
                t.nextRow();
                nextColumn(t);
                text(t, "Cell");
            });
            pushItemWidth(w, 100f);
            popItemWidth(w);
            setNextItemWidth(w, -1f);
            styleVar(w, ImGuiStyleVar.Alpha, 0.5f, faded -> text(faded, "Faded"));
            styleVar(w, ImGuiStyleVar.ItemSpacing, 2f, 2f, tight -> text(tight, "Tight"));
            alignTextToFramePadding(w);
            if (isItemHovered(w) || isItemClicked(w) || isItemClicked(w, MOUSE_LEFT)) text(w, "Hit");
        });
        setNextWindowPosUnsafe(10f, 10f, COND_ONCE, 0f, 0f);
        backgroundDrawList(draw -> {
            draw.tile(3200, 3200, 0, ImGuiColors.INSTANCE.getGREEN());
            draw.textOnTile(3200, 3200, 0, ImGuiColors.INSTANCE.getWHITE(), "Here");
        });
    }

    /** A state machine in Java, phases as an enum. */
    public static final class Miner extends JavaStateMachineScript<Miner> {
        @Override
        public JavaState<Miner> getStartState() {
            return Phase.MINING;
        }
    }

    enum Phase implements JavaState<Miner> {
        MINING {
            @Override
            public JavaState<Miner> checkNext(Miner script) {
                return getInventory().isFull() ? BANKING : null;
            }

            @Override
            public Wait onLoop(Miner script) {
                return interactClosestObject("Rocks", "Mine") ? Wait.xpDrop() : Wait.ms(300, 100);
            }
        },
        BANKING {
            @Override
            public JavaState<Miner> checkNext(Miner script) {
                return getInventory().isEmpty() ? MINING : null;
            }

            @Override
            public Wait onLoop(Miner script) {
                return Wait.webWalk(3185, 3436, 0);
            }
        }
    }
}
