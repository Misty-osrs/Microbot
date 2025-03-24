package net.runelite.client.plugins.microbot.pluginscheduler;

import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.pluginscheduler.type.Scheduled;
import net.runelite.client.plugins.microbot.util.security.Login;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@PluginDescriptor(
        name = PluginDescriptor.Mocrosoft + "Plugin Scheduler",
        description = "Schedule plugins at your will",
        tags = {"microbot", "schedule", "automation"},
        enabledByDefault = false
)
public class SchedulerPlugin extends Plugin {
    @Inject
    private SchedulerConfig config;

    @Provides
    SchedulerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(SchedulerConfig.class);
    }

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ScheduledExecutorService executorService;

    private NavigationButton navButton;
    private SchedulerPanel panel;
    private ScheduledFuture<?> updateTask;
    private SchedulerWindow schedulerWindow;

    @Getter
    private Scheduled currentPlugin;

    private List<Scheduled> scheduledPlugins = new ArrayList<>();

    public boolean isRunning() {
        return currentPlugin != null && currentPlugin.isRunning();
    }
    private ScheduledFuture<?> pluginStopTask;

    @Override
    protected void startUp() {
        panel = new SchedulerPanel(this, config);

        final BufferedImage icon = ImageUtil.loadImageResource(SchedulerPlugin.class, "icon.png");
        navButton = NavigationButton.builder()
                .tooltip("Plugin Scheduler")
                .priority(10)
                .icon(icon)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);

        // Load saved schedules from config
        loadScheduledPlugin();

        // Run the main loop
        updateTask = executorService.scheduleAtFixedRate(() -> {
            SwingUtilities.invokeLater(() -> {
                checkSchedule();
                updatePanels();
            });
        }, 10, 1, TimeUnit.SECONDS);
    }

    public void openSchedulerWindow() {
        if (schedulerWindow == null) {
            schedulerWindow = new SchedulerWindow(this);
        }

        if (!schedulerWindow.isVisible()) {
            schedulerWindow.setVisible(true);
        } else {
            schedulerWindow.toFront();
            schedulerWindow.requestFocus();
        }
    }

    @Override
    protected void shutDown() {
        clientToolbar.removeNavigation(navButton);
        stopCurrentPlugin();

        if (updateTask != null) {
            updateTask.cancel(false);
            updateTask = null;
        }

        if (schedulerWindow != null) {
            schedulerWindow.dispose();
            schedulerWindow = null;
        }
    }

    private void checkSchedule() {
        long currentTime = System.currentTimeMillis();

        for (Scheduled plugin : scheduledPlugins) {
            if (plugin.isDueToRun(currentTime) && !isRunning()) {
                // Run the plugin
                startPlugin(plugin);
                saveScheduledPlugins();

                // Schedule plugin to stop if it has a duration
                if (plugin.getDuration() != null && !plugin.getDuration().isEmpty()) {
                    schedulePluginStop(plugin);
                }

                // Only run one plugin at a time
                break;
            }
        }
    }

    private void schedulePluginStop(Scheduled plugin) {
        // Cancel any existing stop task
        if (pluginStopTask != null && !pluginStopTask.isDone()) {
            pluginStopTask.cancel(false);
            pluginStopTask = null;
        }

        long durationMinutes = plugin.getDurationMinutes();
        if (durationMinutes > 0) {
            pluginStopTask = executorService.schedule(
                    this::stopCurrentPlugin,
                    durationMinutes,
                    TimeUnit.MINUTES
            );
        }
    }

    public void startPlugin(Scheduled plugin) {
        if (plugin == null) return;
        log.info("Starting scheduled plugin: " + plugin.getCleanName());
        currentPlugin = plugin;

        if (!plugin.start()) {
            log.error("Failed to start plugin: " + plugin.getCleanName());
            currentPlugin = null;
            return;
        }

        if (!Microbot.isLoggedIn()) {
            Microbot.getClientThread().runOnClientThread(Login::new);
        }
        updatePanels();
    }

    public void stopCurrentPlugin() {
        if (currentPlugin != null) {
            log.info("Stopping current plugin: " + currentPlugin.getCleanName());
            if (currentPlugin.stop()) {
                currentPlugin = null;
            } else {
                log.error("Failed to stop plugin: " + currentPlugin.getCleanName());
            }
        }
        updatePanels();
    }

    @Subscribe
    public void onPluginChanged(PluginChanged event) {
        if (currentPlugin != null && event.getPlugin() == currentPlugin.getPlugin() && !currentPlugin.isRunning()) {
            currentPlugin = null;
            updatePanels();
        }
    }

    /**
     * Update all UI panels with the current state
     */
    private void updatePanels() {
        if (panel != null) {
            panel.refresh();
        }

        if (schedulerWindow != null && schedulerWindow.isVisible()) {
            schedulerWindow.refresh();
        }
    }

    public void addScheduledPlugin(Scheduled plugin) {
        plugin.setLastRunTime(System.currentTimeMillis());
        scheduledPlugins.add(plugin);
        saveScheduledPlugins();
    }

    public void removeScheduledPlugin(Scheduled plugin) {
        scheduledPlugins.remove(plugin);
        saveScheduledPlugins();
    }

    public void updateScheduledPlugin(Scheduled oldPlugin, Scheduled newPlugin) {
        int index = scheduledPlugins.indexOf(oldPlugin);
        if (index >= 0) {
            scheduledPlugins.set(index, newPlugin);
            saveScheduledPlugins();
        }
    }

    public List<Scheduled> getScheduledPlugins() {
        return new ArrayList<>(scheduledPlugins);
    }

    public void saveScheduledPlugins() {
        // Convert to JSON and save to config
        String json = Scheduled.toJson(scheduledPlugins);
        config.setScheduledPlugins(json);
    }

    private void loadScheduledPlugin() {
        // Load from config and parse JSON
        String json = config.scheduledPlugins();
        if (json != null && !json.isEmpty()) {
            scheduledPlugins = Scheduled.fromJson(json);
        }
    }

    public List<String> getAvailablePlugins() {
        return Microbot.getPluginManager().getPlugins().stream()
                .filter(plugin -> {
                    PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
                    return descriptor != null && descriptor.canBeScheduled();
                })
                .map(Plugin::getName)
                .sorted()
                .collect(Collectors.toList());
    }

    public Scheduled getNextScheduledPlugin() {
        if (scheduledPlugins.isEmpty()) {
            return null;
        }

        Scheduled nextPlugin = null;
        long earliestNextRun = Long.MAX_VALUE;

        for (Scheduled plugin : scheduledPlugins) {
            if (!plugin.isEnabled()) {
                continue;
            }

            if (plugin.getNextRunTime() < earliestNextRun) {
                earliestNextRun = plugin.getNextRunTime();
                nextPlugin = plugin;
            }
        }

        return nextPlugin;
    }
}
