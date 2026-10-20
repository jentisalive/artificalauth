package fun.vespera.dialogAuthVelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;


@Plugin(id = "dialogauthvelocity", name = "DialogAuthVelocity", version = "1.2", authors = {"MiFo"})
public class DialogAuthVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private ConfigurationNode config;

    @Inject
    public DialogAuthVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;

    }


    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();

        server.getEventManager().register(this, new LoginListener(this));
        server.getCommandManager().register("license", new LicenseCommand(this));

        logger.info("VesperaVelocityAuth успешно запущен!");

        var mm = MiniMessage.miniMessage();

        server.getConsoleCommandSource().sendMessage(mm.deserialize(""));
        server.getConsoleCommandSource().sendMessage(mm.deserialize("<#BDB6E7>  _____  _       _                           _   _     "));
        server.getConsoleCommandSource().sendMessage(mm.deserialize("<#BDB6E7> |  __ \\(_)     | |               /\\        | | | |    "));
        server.getConsoleCommandSource().sendMessage(mm.deserialize("<#BDB6E7> | |  | |_  __ _| | ___   __ _   /  \\  _   _| |_| |__  "));
        server.getConsoleCommandSource().sendMessage(mm.deserialize("<#BDB6E7> | |  | | |/ _` | |/ _ \\ / _` | / /\\ \\| | | | __| '_ \\ "));
        server.getConsoleCommandSource().sendMessage(mm.deserialize("<#BDB6E7> | |__| | | (_| | | (_) | (_| |/ ____ \\ |_| | |_| | | |"));
        server.getConsoleCommandSource().sendMessage(mm.deserialize("<#BDB6E7> |_____/|_|\\__,_|_|\\___/ \\__, /_/    \\_\\__,_|\\__|_| |_|"));
        server.getConsoleCommandSource().sendMessage(mm.deserialize("<#BDB6E7>                          __/ |                        "));
        server.getConsoleCommandSource().sendMessage(mm.deserialize("<#BDB6E7>                         |___/      <#64F8A7>ᴠᴇʟᴏᴄɪᴛʏ ᴀᴅᴅᴏɴ"));
        server.getConsoleCommandSource().sendMessage(mm.deserialize(""));

    }

    private void loadConfig() {
        try {
            // make plugin folder is there is no plugin folder
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            // make config.yml and copy plugins default config
            File configFile = new File(dataDirectory.toFile(), "config.yml");
            if (!configFile.exists()) {
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile.toPath());
                    } else {
                        configFile.createNewFile();
                    }
                }
            }

            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                    .path(configFile.toPath())
                    .build();
            config = loader.load();
        } catch (Exception e) {
            logger.error("an error occurred while loading config.yml", e);
        }
    }

    public ConfigurationNode getConfig() {
        return config;
    }

    public Logger getLogger() {
        return logger;
    }
}