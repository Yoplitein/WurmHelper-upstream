package net.ildar.wurm.bot;

import com.wurmonline.client.comm.ServerConnectionListenerClass;
import com.wurmonline.client.renderer.GroundItemData;
import com.wurmonline.client.renderer.cell.GroundItemCellRenderable;
import com.wurmonline.client.renderer.cell.StaticModelRenderable;
import com.wurmonline.shared.constants.PlayerAction;
import net.ildar.wurm.WurmHelper;
import net.ildar.wurm.Utils;
import net.ildar.wurm.annotations.BotInfo;

import java.util.*;

@BotInfo(description =
        "Collects items from the ground around player.",
        abbreviation = "gig")
public class GroundItemGetterBot extends Bot {
    private Set <String> itemNames = new HashSet<>();
    private float distance = 4;

    public GroundItemGetterBot() {
        registerInputHandler(GroundItemGetterBot.InputKey.a, this::addNewItemName);
        registerInputHandler(GroundItemGetterBot.InputKey.d, this::setDistance);
    }
    @Override
    public void work() throws Exception{
        setTimeout(500);
        while (isActive()) {
            waitOnPause();
            if (itemNames.size() > 0) {
                ServerConnectionListenerClass sscc = WurmHelper.hud.getWorld().getServerConnection().getServerConnectionListener();
                Map<Long, GroundItemCellRenderable> groundItems = Utils.getField(sscc, "groundItems");
                float x = WurmHelper.hud.getWorld().getPlayerPosX();
                float y = WurmHelper.hud.getWorld().getPlayerPosY();
                if (groundItems.size() > 0)
                    try {
                        for (Map.Entry<Long, GroundItemCellRenderable> entry : groundItems.entrySet()) {
                            GroundItemData groundItemData = Utils.getField(entry.getValue(), "item");
                            float itemX = groundItemData.getX();
                            float itemY = groundItemData.getY();
                            if ((Math.sqrt(Math.pow(itemX - x, 2) + Math.pow(itemY - y, 2)) <= distance) && itemNames != null && itemNames.size() > 0)
                                for (String item : itemNames)
                                    if (groundItemData.getName().contains(item))
                                        WurmHelper.hud.sendAction(PlayerAction.TAKE, groundItemData.getId());
                        }
                    } catch (ConcurrentModificationException ignored) {
                    }
            }
            sleep(timeout);
        }
    }

    @SuppressWarnings("unused")
    public void processNewItem(StaticModelRenderable staticModelRenderable) {
        try {
            float x = WurmHelper.hud.getWorld().getPlayerPosX();
            float y = WurmHelper.hud.getWorld().getPlayerPosY();
            float itemX = Utils.getField(staticModelRenderable, "x");
            float itemY = Utils.getField(staticModelRenderable, "y");
            if ((Math.sqrt(Math.pow(itemX-x, 2)+Math.pow(itemY-y, 2)) <= distance) && itemNames != null && itemNames.size() > 0)
                for(String item:itemNames)
                    if (staticModelRenderable.getHoverName().contains(item))
                        WurmHelper.hud.sendAction(PlayerAction.TAKE, staticModelRenderable.getId());
        }
        catch(IllegalAccessException|NoSuchFieldException e) {
            Utils.consolePrint("Got exception while processing new item in " + GroundItemGetterBot.class.getSimpleName());
        }
    }

    private void addNewItemName(String []input) {
        if (input == null || input.length < 1) {
            printInputKeyUsageString(GroundItemGetterBot.InputKey.a);
            return;
        }
        StringBuilder newitem = new StringBuilder(input[0]);
        for (int i = 1; i < input.length; i++)
            newitem.append(" ").append(input[i]);
        addItem(newitem.toString());
    }

    private void setDistance(String []input) {
        if (input == null || input.length != 1) {
            printInputKeyUsageString(GroundItemGetterBot.InputKey.d);
            return;
        }
        try {
            distance = Float.parseFloat(input[0]);
            Utils.consolePrint("Distance was set to " + distance + " meters");
        } catch (NumberFormatException e) {
            Utils.consolePrint("Wrong distance value!");
        }
    }

    private void addItem(String item) {
        if (itemNames == null)
            itemNames = new HashSet<>();
        itemNames.add(item);
        Utils.consolePrint("Current item set in " + this.getClass().getSimpleName() + " - " + itemNames.toString());
    }

    enum InputKey implements Bot.InputKey {
        d("Set the distance the bot should look around player in search for items",
                "distance(in meters, 1 tile is 4 meters)"),
        a("Add new item name to search list", "item_name");

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
