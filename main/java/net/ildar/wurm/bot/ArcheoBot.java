package net.ildar.wurm.bot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.renderer.gui.CreationWindow;
import com.wurmonline.client.renderer.gui.InventoryListComponent;
import com.wurmonline.client.renderer.gui.InventoryWindow;
import com.wurmonline.client.renderer.gui.ItemListWindow;
import com.wurmonline.client.renderer.gui.WurmComponent;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.shared.constants.PlayerAction;

import net.ildar.wurm.Utils;
import net.ildar.wurm.WurmHelper;
import net.ildar.wurm.Utils.Vec2i;
import net.ildar.wurm.annotations.BotInfo;

@BotInfo(
	description = "Investigates tiles and identifies fragments",
	abbreviation = "ac"
)
public class ArcheoBot extends Bot {
	static final int identifyChisel = 1201;
	static final int identifyBrush = 882;

	static final Pattern unidentifiedRegex = Pattern.compile("unidentified .*fragment");
	static final Pattern identifiedRegex = Pattern.compile("^.+ fragment \\[\\d+/\\d+\\]");

	boolean investigating = false;
	boolean identifying = false;
	boolean combining = false;
	boolean useShovel = false;

	long trowelId = -1;
	long shovelId = -1;
	long chiselId = -1;
	long brushId = -1;

	List<InventoryListComponent> identifyInventories = new ArrayList<>();

	AreaAssistant areaAssistant = new AreaAssistant(this);

	boolean investigatingDone = false;

	public ArcheoBot() {
		registerInputHandler(InputKey.iv, this::toggleInvestigating);
		registerInputHandler(InputKey.id, this::toggleIdentifying);
		registerInputHandler(InputKey.co, this::toggleCombining);
		registerInputHandler(InputKey.sh, this::toggleUseShovel);
		registerInputHandler(InputKey.at, this::setIdentifyInventory);
		registerInputHandler(InputKey.ct, this::clearIdentifyInventory);

		registerEventProcessor(
			msg -> {
				msg = msg.toLowerCase();
				return
					msg.contains("the area looks picked clean") ||
					msg.contains("you pick out a fragment of some item") ||
					msg.contains("you can't find any traces of any abandoned settlements here")
				;
			},
			() -> investigatingDone = true
		);

		areaAssistant.setMoveAheadDistance(3);
		areaAssistant.setMoveRightDistance(3);
	}

	@Override
	public void work() throws Exception {
		try {
			trowelId = Utils.locateToolItem("trowel").getId();
			shovelId = Utils.locateToolItem("shovel").getId();
			chiselId = Utils.locateToolItem("chisel").getId();
			brushId = Utils.locateToolItem("metal brush").getId();
		} catch(NullPointerException err) {
			Utils.consolePrint("Missing required archaeology tools");
			return;
		}

		Set<Vec2i> tilesInvestigated = new HashSet<>();
		List<Vec2i> tilesToBeInvestigated = new ArrayList<>();

		List<InventoryMetaItem> unidentified = new ArrayList<>();
		
		Map<String, List<InventoryMetaItem>> uncombined = new HashMap<>();

		final int maxActions = Utils.getMaxActionNumber();

		outer: while(isActive()) {
			waitOnPause();

			boolean didWork = false;

			if(investigating) {
				final Vec2i curPos = new Vec2i(
					WurmHelper.hud.getWorld().getPlayerCurrentTileX(),
					WurmHelper.hud.getWorld().getPlayerCurrentTileY()
				);
				if(tilesToBeInvestigated.isEmpty()) {
					for(int y = -1; y < 2; y++)
						for(int x = -1; x < 2; x++) {
							final Vec2i next = new Vec2i(curPos.x + x,curPos.y + y);
							if(tilesInvestigated.contains(next))
								continue;
							tilesToBeInvestigated.add(next);
						}
					
					if(tilesToBeInvestigated.isEmpty()) {
						Thread.sleep(1000);
						areaAssistant.areaNextPosition();
						continue outer;
					}
				}

				if(!tilesToBeInvestigated.isEmpty()) {
					didWork = true;
					investigatingDone = false;
					
					final Vec2i tile = tilesToBeInvestigated.get(tilesToBeInvestigated.size() - 1);
					if(Math.max(Math.abs(tile.x - curPos.x), Math.abs(tile.y - curPos.y)) > 1) {
						tilesToBeInvestigated.remove(tilesToBeInvestigated.size() - 1);
					} else {
						final long tool = useShovel ? shovelId : trowelId;
						WurmHelper.hud.getWorld().getServerConnection().sendAction(
							tool,
							new long[]{Tiles.getTileId(tile.x, tile.y, 0)},
							PlayerAction.INVESTIGATE
						);
						
						if(waitActionStarted(() -> investigatingDone))
						waitActionFinished();
						if(investigatingDone) {
							tilesToBeInvestigated.remove(tilesToBeInvestigated.size() - 1);
							tilesInvestigated.add(tile);
						}
					}
				}
			}

			if(identifying) {
				unidentified.clear();
				for(InventoryListComponent ilc: identifyInventories) {
					List<InventoryMetaItem> found = Utils.getInventoryItems(
						ilc,
						item -> unidentifiedRegex.matcher(item.getDisplayName()).find()
					);
					if(found != null)
						unidentified.addAll(found);
				}
				
				Iterator<InventoryMetaItem> iter = unidentified.iterator();
				boolean anyQueued = false;
				for(int action = 0; action < maxActions; action++) {
					if(!iter.hasNext()) break;
					anyQueued = true;


					InventoryMetaItem fragment = iter.next();
					int improveIcon = fragment.getImproveIconId();
					if(improveIcon == identifyChisel) {
						WurmHelper.hud.getWorld().getServerConnection().sendAction(
							chiselId,
							new long[]{fragment.getId()},
							PlayerAction.IDENTIFY
						);
					} else if(improveIcon == identifyBrush) {
						WurmHelper.hud.getWorld().getServerConnection().sendAction(
							brushId,
							new long[]{fragment.getId()},
							PlayerAction.IDENTIFY
						);
					} else {
						Utils.consolePrint("Don't know how to identify fragment `%s`!", fragment.getDisplayName());
						action--;
					}
				}
				
				if(anyQueued) {
					didWork = true;
					if(!waitActionStarted(null))
						continue outer;
					waitActionFinished();
				}
			}

			if(combining) {
				uncombined.clear();
				List<InventoryMetaItem> rawItems = Utils.getInventoryItems(
					item -> identifiedRegex.matcher(item.getBaseName()).find()
				);
				for(InventoryMetaItem item: rawItems) {
					// `ore fragment [1/3], iron` => `iron ore`
					String type = item.getBaseName();
					String displayName = item.getDisplayName();
					int material = displayName.indexOf(", ");
					if(material != -1)
						type = String.join(" ", displayName.substring(material + 2), type);
					type = type.substring(0, type.indexOf(" fragment"));
					
					uncombined.compute(type, (k, v) -> {
						if(v == null) {
							return new ArrayList<>(Collections.singletonList(item));
						} else {
							v.add(item);
							return v;
						}
					});
				}

				int actions = maxActions;
				for(List<InventoryMetaItem> items: uncombined.values()) {
					if(actions <= 0)
						break;
					if(items.size() < 2)
						continue;
					
					InventoryMetaItem head = items.get(0);
					long[] fragments = items
						.stream()
						.skip(1)
						.map(InventoryMetaItem::getId)
						.mapToLong(v -> v)
						.toArray();
					actions -= fragments.length;
					WurmHelper.hud.getWorld().getServerConnection().sendAction(
						head.getId(),
						fragments,
						PlayerAction.COMBINE_FRAGMENT
					);
				}
				if(actions != maxActions) {
					didWork = true;
					if(!waitActionStarted(null))
						continue outer;
					waitActionFinished();
				}
			}

			if(!didWork)
				Thread.sleep(500);
		}
	}

	void toggleInvestigating(String[] input) {
		investigating = !investigating;
		Utils.consolePrint("Bot will%s investigate", investigating ? "" : " no longer");
	}

	void toggleIdentifying(String[] input) {
		identifying = !identifying;
		Utils.consolePrint("Bot will%s identify fragments", identifying ? "" : " no longer");
	}

	void toggleCombining(String[] input) {
		combining = !combining;
		Utils.consolePrint("Bot will%s combine fragments", combining ? "" : " no longer");
	}

	void toggleUseShovel(String[] input) {
		useShovel = !useShovel;
		Utils.consolePrint("Bot will use %s to investigate", useShovel ? "shovel" : "trowel");
	}

	void setIdentifyInventory(String[] input) {
		WurmComponent inventoryComponent = Utils.getTargetComponent(c -> c instanceof ItemListWindow || c instanceof InventoryWindow);
        if (inventoryComponent == null) {
            Utils.consolePrint("Couldn't find an inventory under the cursor");
            return;
        }
        InventoryListComponent ilc;
        try {
            ilc = Utils.getField(inventoryComponent, "component");
        } catch(Exception e) {
            Utils.consolePrint("Unable to get inventory information");
            return;
        }
        identifyInventories.add(ilc);

		String title;
		try {
			title = Utils.getField(inventoryComponent, "title");
		} catch(IllegalAccessException | NoSuchFieldException err) {
			title = "<unknown>";
		}
		Utils.consolePrint("Bot will now identify fragments in %s", title);
	}

	void clearIdentifyInventory(String[] input) {
		identifyInventories.clear();
		Utils.consolePrint("Fragment inventories cleared");
	}

	boolean waitActionStarted(Supplier<Boolean> failed) throws Exception {
		CreationWindow creationWindow = WurmHelper.hud.getCreationWindow();
        Object progressBar = Utils.getField(creationWindow, "progressBar");
		final long start = System.currentTimeMillis();
		while(isActive()) {
			if(failed != null && failed.get()) {
				return false;
			}

			float progress = Utils.getField(progressBar, "progress");
			if(progress != 0f) {
				return true;
			}
			Thread.sleep(250);

			final long now = System.currentTimeMillis();
			if(now - start > 10_000) {
				Utils.consolePrint("Timed out waiting for action to start");
				return false;
			}
		}
		return false;
	}
	
	void waitActionFinished() throws Exception {
		CreationWindow creationWindow = WurmHelper.hud.getCreationWindow();
        Object progressBar = Utils.getField(creationWindow, "progressBar");
		while(isActive()) {
			float progress = Utils.getField(progressBar, "progress");
			if(progress == 0f) {
				break;
			}
			Thread.sleep(250);
		}
	}

	enum InputKey implements Bot.InputKey {
		iv("Toggle investigating", ""),
		id("Toggle identifying", ""),
		co("Toggle fragment combining", ""),
		sh("Toggle investigating with shovel", ""),
		at("Add target inventory to identify fragments in", ""),
		ct("Clear inventories to identify fragments in", ""),
		;

		public String description;
        public String usage;
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
