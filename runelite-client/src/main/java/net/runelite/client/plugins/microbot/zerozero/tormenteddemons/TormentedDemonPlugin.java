package net.runelite.client.plugins.microbot.zerozero.tormenteddemons;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GraphicsObject;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;

import java.awt.datatransfer.StringSelection;
import java.util.StringJoiner;
import java.util.concurrent.Executors;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@PluginDescriptor(
        name = PluginDescriptor.zerozero + "Tormented Demons",
        description = "Automates restocking, prayer flicking, and gear switching during Tormented Demon",
        tags = {"tormented", "flicker", "weapon", "switch"},
        enabledByDefault = false
)
@Slf4j
public class TormentedDemonPlugin extends Plugin {

    private ScheduledExecutorService scheduledExecutorService;

    @Inject
    private TormentedDemonConfig config;

    @Provides
    TormentedDemonConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(TormentedDemonConfig.class);
    }

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private TormentedDemonOverlay tormentedDemonOverlay;

    @Inject
    private TormentedDemonScript tormentedDemonScript;

    @Override
    protected void startUp() throws AWTException {
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        if (overlayManager != null) {
            overlayManager.add(tormentedDemonOverlay);
        }
        tormentedDemonScript.run(config);
    }

    @Override
    protected void shutDown() {
        tormentedDemonScript.shutdown();
        overlayManager.remove(tormentedDemonOverlay);
        if (scheduledExecutorService != null && !scheduledExecutorService.isShutdown()) {
            scheduledExecutorService.shutdown();
        }
    }
    @Subscribe
    private void onConfigChanged(ConfigChanged event) {
        if (event.getGroup().equals("tormenteddemon") && event.getKey().equals("copyGear")) {
            Microbot.getClientThread().invoke(() -> {
                try {
                    StringJoiner gearList = new StringJoiner(",");
                    for (Item item : Microbot.getClient().getItemContainer(InventoryID.EQUIPMENT).getItems()) {
                        if (item != null && item.getId() != -1 && item.getId() != 6512) {
                            ItemComposition itemComposition = Microbot.getClient().getItemDefinition(item.getId());
                            gearList.add(itemComposition.getName());
                        }
                    }

                    String gearString = gearList.toString();
                    if (gearString.isEmpty()) {
                        Microbot.log("No gear found to copy.");
                        return;
                    }

                    StringSelection selection = new StringSelection(gearString);
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
                    Microbot.log("Current gear copied to clipboard: " + gearString);
                } catch (Exception e) {
                    Microbot.log("Failed to copy gear to clipboard: " + e.getMessage());
                }
            });
        }
    }


    @Subscribe
    public void onGraphicsObjectCreated(GraphicsObjectCreated event) {
        final GraphicsObject graphicsObject = event.getGraphicsObject();
        final int TORMENTED_VENGENCE_SPECIAL = 2856;

        if (graphicsObject.getId() == TORMENTED_VENGENCE_SPECIAL) {
            Rs2Tile.init();
            int ticks = 4;

            // Pause all other scripts to prioritize dodging
            Microbot.pauseAllScripts = true;
            try {
                // Schedule dodging with a delay
                scheduledExecutorService.schedule(() -> {
                    Rs2Tile.addDangerousGraphicsObjectTile(graphicsObject, 600 * ticks);

                    // Resume all other scripts after dodging
                    Microbot.pauseAllScripts = false;
                }, 1200, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // Ensure scripts resume even if there's an exception
                Microbot.pauseAllScripts = false;
                System.err.println("Error during dodging: " + e.getMessage());
            }
        }
    }

}
