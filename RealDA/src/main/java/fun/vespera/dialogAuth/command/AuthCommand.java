package fun.vespera.dialogAuth.command;

import fun.vespera.dialogAuth.DatabaseManager;
import fun.vespera.dialogAuth.DialogAuth;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.NotNull;
import org.mindrot.jbcrypt.BCrypt;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@SuppressWarnings("UnstableApiUsage")
public class AuthCommand implements CommandExecutor, Listener {
    private final DialogAuth plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private static final Key CHANGE_PASS_KEY = Key.key("diaologauth:auth_changepass");

    public AuthCommand(DialogAuth plugin) {
        this.plugin = plugin;
    }

    // showing dialog if command is /changepass
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().toLowerCase();
        if (message.startsWith("/changepassword") || message.startsWith("/changepass")) {
            event.setCancelled(true);
            openChangePasswordDialog(event.getPlayer());
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String cmdName = command.getName().toLowerCase();

        switch (cmdName) {
            case "forceunreg" -> handleForceUnreg(sender, args);
            case "forcechangepass" -> handleForceChangePass(sender, args);
            case "dauth" -> handleReload(sender, args);
        }
        return true;
    }

    private void openChangePasswordDialog(Player player) {
        // defining config values
        String title = plugin.getConfig().getString("messages.changepass_title");
        String bodyText = plugin.getConfig().getString("messages.changepass_body");
        String oldLabel = plugin.getConfig().getString("messages.changepass_old_label");
        String newLabel = plugin.getConfig().getString("messages.changepass_new_label");
        String changepassButtonLabel = plugin.getConfig().getString("messages.changepassbutton_label");
        int changepassButtonWidth = plugin.getConfig().getInt("messages.changepassbutton_width");

        // building dialog
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(mm.deserialize(title))
                        .body(List.of(DialogBody.plainMessage(mm.deserialize(bodyText))))
                        .inputs(List.of(
                                DialogInput.text("old_password", Component.text(oldLabel, NamedTextColor.GRAY)).maxLength(64).width(200).build(),
                                DialogInput.text("new_password", Component.text(newLabel, NamedTextColor.GRAY)).maxLength(64).width(200).build()
                        )).build())
                .type(DialogType.multiAction(List.of(
                        ActionButton.create(Component.text(changepassButtonLabel), null, changepassButtonWidth, DialogAction.customClick(CHANGE_PASS_KEY, null))
                )).build())
        );

        player.showDialog(dialog);
    }

    @EventHandler
    public void onClick(PlayerCustomClickEvent event) {
        if (!event.getIdentifier().equals(CHANGE_PASS_KEY)) return;

        if (!(event.getCommonConnection() instanceof PlayerGameConnection gameConn)) {
            return;
        }

        Player player = gameConn.getPlayer();

        DialogResponseView view = event.getDialogResponseView();
        String oldPass = view.getText("old_password");
        String newPass = view.getText("new_password");

        player.closeDialog();

        // if player doesnt typed any password, telling hin
        if (oldPass == null || oldPass.isEmpty() || newPass == null || newPass.isEmpty() || newPass.length() > 64) {
            player.sendMessage(mm.deserialize(plugin.getConfig().getString("messages.missing_password")));
            return;
        }

        //if new pass to short, telling it player
        int minLength = plugin.getConfig().getInt("security.min_length", 6);
        if (newPass.length() < minLength) {
            String shortMsg = plugin.getConfig().getString("messages.password_too_short")
                    .replace("{min}", String.valueOf(minLength));
            player.sendMessage(mm.deserialize(shortMsg));
            return;
        }

        int rounds = plugin.getConfig().getInt("security.bcrypt_rounds", 10);

        CompletableFuture.runAsync(() -> {
            DatabaseManager.PlayerData data = plugin.getDb().getPlayerData(player.getName()).join();
            // checking is oldPass correct
            if (data != null && BCrypt.checkpw(oldPass, data.passwordHash())) {
                // hash-hash-hashing
                String newHash = BCrypt.hashpw(newPass, BCrypt.gensalt(rounds));
                plugin.getDb().savePlayer(new DatabaseManager.PlayerData(data.uuid(), data.username(), newHash, data.lastIp(), data.lastLogin(), data.isPremium()));
                // success
                player.sendMessage(mm.deserialize(plugin.getConfig().getString("messages.password_changed")));
            } else {
                // wrong password
                player.sendMessage(mm.deserialize(plugin.getConfig().getString("messages.wrong_password")));
            }
        }, plugin.getAsyncExecutor());
    }

    private void handleForceUnreg(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dialogauth.admin")) {
            sender.sendMessage(mm.deserialize(plugin.getConfig().getString("messages.no_permission")));
            return;
        }
        if (args.length < 1) {
            sender.sendMessage(mm.deserialize(plugin.getConfig().getString("messages.forceunreg_usage")));
            return;
        }

        // deleting player from db
        plugin.getDb().deletePlayer(args[0]).thenRun(() ->
                sender.sendMessage(mm.deserialize(plugin.getConfig().getString("messages.force_unregistered").replace("{player}", args[0]))));
    }

    private void handleForceChangePass(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dialogauth.admin")) {
            sender.sendMessage(mm.deserialize(plugin.getConfig().getString("messages.no_permission")));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(mm.deserialize(plugin.getConfig().getString("messages.forcechangepass_usage")));
            return;
        }

        int rounds = plugin.getConfig().getInt("security.bcrypt_rounds", 10);

        CompletableFuture.runAsync(() -> {
            DatabaseManager.PlayerData data = plugin.getDb().getPlayerData(args[0]).join();
            if (data != null) {
                // hash-hash-hashing
                String newHash = BCrypt.hashpw(args[1], BCrypt.gensalt(rounds));
                plugin.getDb().savePlayer(new DatabaseManager.PlayerData(data.uuid(), data.username(), newHash, data.lastIp(), data.lastLogin(), data.isPremium()));
                sender.sendMessage(mm.deserialize(plugin.getConfig().getString("messages.force_password_changed").replace("{player}", args[0])));
            } else {
                sender.sendMessage(mm.deserialize(plugin.getConfig().getString("messages.player_not_found")));
            }
        }, plugin.getAsyncExecutor());
    }

    private void handleReload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dialogauth.admin")) {
            sender.sendMessage(mm.deserialize(plugin.getConfig().getString("messages.no_permission")));
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            // reload confing
            plugin.reloadConfig();
            sender.sendMessage(mm.deserialize(plugin.getConfig().getString("messages.config_reloaded")));
        } else {
            sender.sendMessage(mm.deserialize(plugin.getConfig().getString("messages.reload_usage")));
        }
    }
}