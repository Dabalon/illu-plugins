package net.runelite.client.plugins.iutils.scripts;

import com.google.inject.Injector;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.iutils.Spells;
import net.runelite.client.plugins.iutils.api.*;
import net.runelite.client.plugins.iutils.game.*;
import net.runelite.client.plugins.iutils.scene.Area;
import net.runelite.client.plugins.iutils.scene.RectangularArea;
import net.runelite.client.plugins.iutils.ui.*;
import net.runelite.client.plugins.iutils.util.Util;
import net.runelite.client.plugins.iutils.walking.BankLocations;
import net.runelite.client.plugins.iutils.walking.Walking;

import javax.inject.Inject;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
public abstract class UtilsScript extends Plugin {
    protected static final RectangularArea GRAND_EXCHANGE = new RectangularArea(3156, 3492, 3168, 3484);

    @Inject
    protected Game game;
    @Inject
    protected Walking walking;
    @Inject
    protected Chatbox chatbox;
    @Inject
    protected Equipment equipment;
    @Inject
    protected Combat combat;
    @Inject
    protected StandardSpellbook standardSpellbook;
    @Inject
    protected Prayers prayers;
    @Inject
    protected Injector injector;

    protected void equip(int... ids) {
        obtain(Arrays.stream(ids)
                .filter(i -> !equipment.isEquipped(i))
                .mapToObj(i -> new ItemQuantity(i, 1))
                .toArray(ItemQuantity[]::new));

        game.tick(2);

        game.inventory().withId(ids).forEach(i -> {
            log.info("Equipping: {}", i.name());
            i.interact(1);
            game.tick(2);
        });

        game.waitUntil(() -> Arrays.stream(ids).allMatch(equipment::isEquipped));
    }

    protected void equip(String name) {
        if (game.equipment().withName(name).exists()) {
            return;
        }

        var item = game.inventory().withName(name).first();

        if (item.actions().contains("Wield")) {
            game.inventory().withName(name).first().interact("Wield");
        } else if (item.actions().contains("Wear")) {
            game.inventory().withName(name).first().interact("Wear");
        } else if (item.actions().contains("Equip")) {
            game.inventory().withName(name).first().interact("Equip");
        } else {
            throw new IllegalStateException("no known equip action for item");
        }

        game.waitUntil(() -> game.equipment().withName(name).exists(), 6);
    }

    protected void obtain(ItemQuantity... items) {
        if (hasItems(items)) {
            return;
        }

        obtainBank(items);
        withdraw(items);
    }

    protected void obtain(List<ItemQuantity> items) {
        ItemQuantity[] itemArray = items.toArray(ItemQuantity[]::new);
        if (hasItems(itemArray)) {
            return;
        }
        obtainBank(itemArray);
        withdraw(itemArray);
    }

    protected void withdraw(ItemQuantity... items) {
        Arrays.stream(items)
                .map(i -> new ItemQuantity(i.id, i.quantity - game.inventory().withId(i.id).quantity()))
                .filter(i -> i.quantity > 0)
                .collect(Collectors.toList())
                .forEach(i -> bank().withdraw(i.id, i.quantity, false));
    }

    protected void obtainBank(ItemQuantity... items) {
        if (items.length == 0) {
            return;
        }

        List<ItemQuantity> buyItems = new ArrayList<>();
        List<iWidget> bankItems = bank().items();
        Arrays.stream(items)
                .filter(Objects::nonNull)
                .map(i -> new ItemQuantity(i.id,
                        i.quantity - bankItemQuantity(bankItems, i.id) - game.inventory().withId(i.id).quantity()))
                .filter(i -> i.quantity > 0)
                .collect(Collectors.toList())
                .forEach(i -> {
                    var quantity = i.quantity;

                    if (game.equipment().withId(i.id).exists()) {
                        game.equipment().withId(i.id).first().interact("Remove");
                        quantity -= game.equipment().withId(i.id).first().quantity();
                    }

                    if (quantity > 0) {
                        buyItems.add(i);
                    }
                });
        if (!buyItems.isEmpty()) {
            grandExchange().buy(buyItems);
        }
        bank().depositInventory();
    }

    private int bankItemQuantity(List<iWidget> bankItems, int id) {
        iWidget bankItem = bankItems.stream()
                .filter(i -> i.itemId() == id)
                .findFirst()
                .orElse(null);

        return bankItem == null ? 0 : bankItem.quantity();
    }

    protected boolean inventoryHasItems(ItemQuantity... items) {
        for (var item : items) {
            if (game.inventory().withId(item.id).quantity() < item.quantity) {
                return false;
            }
        }
        return true;
    }

    protected boolean hasItems(ItemQuantity... items) {
        for (var item : items) {
            if (equipment.quantity(item.id) < item.quantity && game.inventory().withId(item.id).quantity() < item.quantity) {
                return false;
            }
        }
        return true;
    }

    protected void handleLevelUp() {
        if (game.widget(162, 562).nestedInterface() == 233) {
            System.out.println("Closing chat dialog");
            game.widget(233, 3).select();
            game.tick();
            chat();
        }
    }

    protected Bank bank() {
        var bank = new Bank(game);

        if (!bank.isOpen()) {
            BankLocations.walkToBank(game);
            if (game.npcs().withName("Banker").withAction("Bank").exists()) {
                game.npcs().withName("Banker").withAction("Bank").nearest().interact("Bank");
            } else if (game.objects().withName("Bank booth").withAction("Bank").exists()) {
                game.objects().withName("Bank booth").withAction("Bank").nearest().interact("Bank");
            } else {
                game.objects().withName("Bank chest").nearest().interact("Use");
            }
            game.waitUntil(bank::isOpen, 10);
//            game.tick();
        }

        return bank;
    }

    protected GrandExchange grandExchange() {
        if (!GRAND_EXCHANGE.contains(game.localPlayer().position())) {
            if (GRAND_EXCHANGE.distanceTo(game.localPlayer().position()) > 50) {
                TeleportMethod varrockTeleport = new TeleportMethod(game, TeleportLocation.VARROCK, 1);
                varrockTeleport.getTeleport(true);
            }
            walking.walkTo(GRAND_EXCHANGE);
        }

        bank().withdraw(995, Integer.MAX_VALUE, false);
        var grandExchange = new GrandExchange(game);

        if (!grandExchange.isOpen()) {
            game.npcs().withName("Grand Exchange Clerk").nearest().interact("Exchange");
            game.waitUntil(grandExchange::isOpen);
        }

        return grandExchange;
    }

    protected void teleportToLumbridge() {
        standardSpellbook.lumbridgeHomeTeleport();
    }

    protected void killNpc(String name, Prayer... prayers) {
        game.waitUntil(() -> game.npcs().withName(name).withAction("Attack").exists());
        var npc = game.npcs().withName(name).nearest();
        killNpc(npc, prayers);
    }

    protected void killNpc(int id, Prayer... prayers) {
        game.waitUntil(() -> game.npcs().withId(id).exists());
        var npc = game.npcs().withId(id).nearest();

        if (game.npcs().withId(id).withTarget(game.localPlayer()).nearest() != null) {
            npc = game.npcs().withId(id).withTarget(game.localPlayer()).nearest();
        }

        killNpc(npc, prayers);
    }

    public void killNpc(iNPC npc, Prayer... prayers) {
        combat.kill(npc, prayers);
    }

    private boolean needsStatRestore() {
        var matters = new Skill[]{Skill.ATTACK, Skill.DEFENCE, Skill.STRENGTH};
        for (var skill : matters) {
            if (game.modifiedLevel(skill) < game.baseLevel(skill)) {
                return true;
            }
        }
        return false;
    }

    protected void chatNpc(Area area, String npcName) {
        if (area != null && !area.contains(game.localPlayer().position())) {
            walking.walkTo(area);
        }

        game.npcs().withName(npcName).nearest().interact("Talk-to");
        chatbox.chat();
        game.tick();
    }

    protected void chatNpc(Area area, int npcId) {
        if (area != null && !area.contains(game.localPlayer().position())) {
            walking.walkTo(area);
        }

        game.npcs().withId(npcId).nearest().interact("Talk-to");
        chatbox.chat();
        game.tick();
    }

    protected void chatNpc(Area area, String npcName, String... chatOptions) {
        if (area != null && !area.contains(game.localPlayer().position())) {
            walking.walkTo(area);
        }

        game.npcs().withName(npcName).nearest().interact("Talk-to");
        chatbox.chat(chatOptions);
        game.tick();
    }

    protected void chatNpc(Area area, int npcId, String... chatOptions) {
        if (area != null && !area.contains(game.localPlayer().position())) {
            walking.walkTo(area);
        }

        game.npcs().withId(npcId).nearest().interact("Talk-to");
        chatbox.chat(chatOptions);
        game.tick();
    }

    protected void chatNpc(Area area, String npcName, int... chatOptions) {
        if (area != null && !area.contains(game.localPlayer().position())) {
            walking.walkTo(area);
        }

        game.npcs().withName(npcName).nearest().interact("Talk-to");
        chatbox.chat(chatOptions);
        game.tick();
    }

    protected void chatNpc(Area area, int npcId, int... chatOptions) {
        if (area != null && !area.contains(game.localPlayer().position())) {
            walking.walkTo(area);
        }

        game.npcs().withId(npcId).nearest().interact("Talk-to");
        chatbox.chat(chatOptions);
        game.tick();
    }

    protected void chatOptionalNpc(Area area, String npcName, String... chatOptions) {
        if (area != null && !area.contains(game.localPlayer().position())) {
            walking.walkTo(area);
        }

        game.npcs().withName(npcName).nearest().interact("Talk-to");
        chatbox.chats(Arrays.asList(chatOptions));
        game.tick();
    }

    protected void unequip(String item, EquipmentSlot slot) {
        if (game.equipment().withName(item).exists()) {
            game.widget(slot.widgetID, slot.widgetChild).interact(0);
            game.tick();
        }
    }

    protected void castSpellNpc(String name, Spells spell) {
        var npc = game.npcs().withName(name).nearest();
        castSpellNpc(npc, spell);
    }

    protected void castSpellNpc(iNPC npc, Spells spell) {
        if (npc != null) {
            game.widget(spell.getInfo()).useOn(npc);
            game.tick();
        }
    }

    protected void castSpellItem(InventoryItem it, Spells spell) {
        if (it != null) {
            game.widget(spell.getInfo()).useOn(it);
            game.tick();
        }
    }

    protected boolean inCombat() {
        return game.npcs().withTarget(game.localPlayer()).exists() || game.localPlayer().target() != null;
    }

    protected boolean itemOnGround(String item) {
        return game.groundItems().withName(item).exists();
    }

    protected void chat(String... options) {
        chatbox.chat(options);
    }

    protected void chat(int n) {
        for (var i = 0; i < n; i++) {
            chat();
        }
    }

    protected void interactObject(Area area, String object, String action) {
        if (!area.contains(game.localPlayer().position())) {
            walking.walkTo(area);
        }
        game.objects().withName(object).withAction(action).nearest().interact(action);
        game.tick();
    }

    protected void interactObject(Area area, int id, String action) {
        if (!area.contains(game.localPlayer().position())) {
            walking.walkTo(area);
        }
        game.objects().withId(id).withAction(action).nearest().interact(action);
        game.tick();
    }

    protected void useItemItem(String item1, String item2) {
        var a = game.inventory().withName(item1).first();
        var b = game.inventory().withName(item2).filter(i -> i.slot() != a.slot()).first();
        a.useOn(b);
    }

    protected void useItemItem(int item1, String item2) {
        game.inventory().withId(item1).first().useOn(game.inventory().withName(item2).first());
    }

    protected void take(Area area, String item) {
        if (area != null) {
            walking.walkTo(area);
        }

        game.waitUntil(() -> game.groundItems().withName(item).exists());
        game.groundItems().withName(item).nearest().interact("Take");
        waitItem(item);
    }

    protected boolean hasItem(String item) {
        return game.inventory().withName(item).exists() || game.equipment().withName(item).exists();
    }

    protected boolean hasItem(int itemId) {
        return game.inventory().withId(itemId).exists() || game.equipment().withId(itemId).exists();
    }

    protected void waitItem(String item) {
        game.waitUntil(() -> game.inventory().withName(item).size() > 0);
    }

    protected void useItemObject(Area area, String item, String object) {
        if (area != null && !area.contains(game.localPlayer().position())) {
            walking.walkTo(area);
        }

        game.inventory().withName(item).first().useOn(game.objects().withName(object).nearest());
    }

    protected void useItemObject(Area area, String item, int object) {
        if (area != null && !area.contains(game.localPlayer().position())) {
            walking.walkTo(area);
        }

        game.inventory().withName(item).first().useOn(game.objects().withId(object).nearest());
    }

    protected void useItemObject(Area area, int item, String object) {
        walking.walkTo(area);
        game.inventory().withId(item).first().useOn(game.objects().withName(object).nearest());
    }

    protected void interactNpc(Area area, String npc, String action) {
        walking.walkTo(area);
        game.npcs().withName(npc).nearest().interact(action);
    }

    protected void interactNpc(Area area, int npc, String action) {
        walking.walkTo(area);
        game.npcs().withId(npc).nearest().interact(action);
    }

    public void waitNpc(String name) {
        game.waitUntil(() -> game.npcs().withName(name).exists());
    }

    public boolean hasItem(String name, int quantity) {
        return game.inventory().withName(name).count() >= quantity;
    }

    protected void interactItem(String item, String action) {
        game.inventory().withName(item).first().interact(action);
    }

    protected void useItemNpc(String item, String npc) {
        game.inventory().withName(item).first().useOn(game.npcs().withName(npc).nearest());
    }

    protected void waitAnimationEnd(int id) {
        game.waitUntil(() -> game.localPlayer().animation() == id);
        game.waitUntil(() -> game.localPlayer().animation() == -1);
    }

    protected boolean logout() {
        if (game.widget(182, 8) != null) {
            game.widget(182, 8).interact("Logout");
        } else {
            game.widget(WidgetInfo.WORLD_SWITCHER_LOGOUT_BUTTON).interact("Logout");
        }
        Util.sleep(3000);
        return game.client.getGameState() == GameState.LOGIN_SCREEN;
    }

    protected void login(String username, String password) {
        Util.sleep(500);
        game.pressKey(KeyEvent.VK_ENTER);
        game.client.setUsername(username);
        game.client.setPassword(password);
        Util.sleep(500);
        game.pressKey(KeyEvent.VK_ENTER);
        game.pressKey(KeyEvent.VK_ENTER);

        game.waitUntil(() -> game.widget(WidgetInfo.LOGIN_CLICK_TO_PLAY_SCREEN) != null);
        if (game.widget(WidgetInfo.LOGIN_CLICK_TO_PLAY_SCREEN) != null) {
            game.widget(378, 78).interact(0);
        }
    }
}
