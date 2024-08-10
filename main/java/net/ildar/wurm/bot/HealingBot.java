package net.ildar.wurm.bot;

import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.gui.CreationWindow;
import com.wurmonline.shared.constants.PlayerAction;
import net.ildar.wurm.WurmHelper;
import net.ildar.wurm.Utils;
import net.ildar.wurm.annotations.BotInfo;

import java.util.*;

@BotInfo(description =
        "Heals the player's wounds with cotton found in inventory",
        abbreviation = "h")
public class HealingBot extends Bot {
    private final Set<String> WOUND_NAMES = new HashSet<>(Arrays.asList("Cut", "Bite", "Bruise", "Burn", "Hole", "Acid", "Infection"));
    private float minDamage = 0;

    public HealingBot() {
        registerInputHandler(HealingBot.InputKey.md, this::setMinimumDamage);
    }

    @Override
    protected void work() throws Exception{
        setTimeout(500);
        CreationWindow creationWindow = WurmHelper.hud.getCreationWindow();
        Object progressBar = Utils.getField(creationWindow, "progressBar");
        while (isActive()) {
            waitOnPause();
            float progress = Utils.getField(progressBar, "progress");
            if (progress != 0f) {
                sleep(timeout);
                continue;
            }
            float damage = WurmHelper.hud.getWorld().getPlayer().getDamage();
            if (damage == 0) {
                Utils.consolePrint("The player is fully healed");
                return;
            }
            InventoryMetaItem cottonItem = Utils.getInventoryItems("cotton").stream().filter(item -> item.getBaseName().equals("cotton")).findFirst().orElse(null);
            if (cottonItem == null) {
                Utils.consolePrint("The player don't have a cotton!");
                return;
            }
            List<InventoryMetaItem> inventoryItems = new ArrayList<>();
            inventoryItems.add(Utils.getRootItem(WurmHelper.hud.getInventoryWindow().getInventoryListComponent()));
            List<InventoryMetaItem> wounds = new ArrayList<>();
            while (inventoryItems.size() > 0) {
                InventoryMetaItem item = inventoryItems.get(0);
                if (WOUND_NAMES.contains(item.getBaseName())
                        && !item.getDisplayName().contains("bandaged")
                        && item.getDamage() > minDamage)
                    wounds.add(item);
                if (item.getChildren() != null)
                    inventoryItems.addAll(item.getChildren());
                inventoryItems.remove(item);
            }
            if (wounds.size() == 0) {
                Utils.consolePrint("All wounds were treated");
                return;
            }
            wounds.sort(Comparator.comparingDouble(InventoryMetaItem::getDamage).reversed());
            int maxActionNumber = Utils.getMaxActionNumber();
            int i = 0;
            for (InventoryMetaItem wound : wounds) {
                WurmHelper.hud.getWorld().getServerConnection().sendAction(cottonItem.getId(), new long[]{wound.getId()}, PlayerAction.FIRSTAID);
                if (++i >= maxActionNumber)
                    break;
            }
            sleep(timeout);
        }
    }

    private void setMinimumDamage(String[] input) {
        if (input == null || input.length != 1) {
            printInputKeyUsageString(HealingBot.InputKey.md);
            return;
        }
        try {
            minDamage = Float.parseFloat(input[0]);
            Utils.consolePrint(String.format("The wound must have damage greater than %.2f in order to be treated", minDamage));
        } catch (NumberFormatException e) {
            Utils.consolePrint("Wrong value!");
        }
    }


    private enum InputKey implements Bot.InputKey {
        md("Set the minimum damage of the wound to be treated", "min_damage");

        private String description;
        private String usage;
        InputKey(String description, String usage) {
            this.description = description;
            this.usage = usage;
        }

        @Override
        public String getName() {
            return name();
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public String getUsage() {
            return usage;
        }
    }
}
