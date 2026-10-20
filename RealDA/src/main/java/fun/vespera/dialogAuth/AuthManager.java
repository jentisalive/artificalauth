package fun.vespera.dialogAuth;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.mindrot.jbcrypt.BCrypt;

import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


@SuppressWarnings("UnstableApiUsage")
public class AuthManager implements Listener {
    private final DialogAuth plugin;
    private final Map<UUID, CompletableFuture<AuthResult>> awaiting = new ConcurrentHashMap<>();

    // (for new ip system)
    private final Map<UUID, String> loginIps = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failedAttempts = new ConcurrentHashMap<>();
    private final Map<String, Long> bannedIps = new ConcurrentHashMap<>();

    private static final Key LOGIN_KEY = Key.key("diaologauth:auth_login");
    private static final Key REGISTER_KEY = Key.key("diaologauth:auth_register");
    private final MiniMessage mm = MiniMessage.miniMessage();

    public AuthManager(DialogAuth plugin) {
        this.plugin = plugin;
    }

    // all register/login result statuses
    private enum AuthResult { SUCCESS, CANCEL, WRONG_PASSWORD, MISSMATCH, TIMEOUT, TOO_SHORT }

    // onPlayerConfigure event happens before you connected
    @EventHandler
    public void onPlayerConfigure(AsyncPlayerConnectionConfigureEvent event) {

        // handle players connection & get nickname, ip
        PlayerConfigurationConnection connection = event.getConnection();
        UUID uuid = connection.getProfile().getId();
        String username = connection.getProfile().getName();

        String ip = plugin.getAndRemovePendingIp(username);
        if (ip == null) {
            ip = ((java.net.InetSocketAddress) connection.getAddress()).getAddress().getHostAddress();
        }

        loginIps.put(uuid, ip);

        // defining domain that player used to connect to server
        InetSocketAddress virtualHost = connection.getVirtualHost();
        String domain = (virtualHost != null) ? virtualHost.getHostString().toLowerCase() : "";


        List<String> noBanDomains = plugin.getConfig().getStringList("security.no_ban_domains");
        boolean bansEnabledForDomain = noBanDomains.stream().noneMatch(d -> domain.contains(d.toLowerCase()));

        // prevent connection if ip banned for rate limit
        if (bansEnabledForDomain && isIpBanned(ip)) {
            connection.disconnect(mm.deserialize(plugin.getConfig().getString("messages.ip_banned")));
            return;
        }

        DatabaseManager.PlayerData data = plugin.getDb().getPlayerData(username).join();

        if (data != null) {
            // dont show dialog if player with PremiumAccount enabled
            // WORKING ONLY WITH API, IF U INSTALLED ADDON, MAKE SURE
            // API SUCCESFULLY STARTED, YOU CAN CHECK IT IN LOGS
            if (data.isPremium()) return;

            // checking are sessions enabled for current domain
            List<String> disabledSessionDomains = plugin.getConfig().getStringList("session.disabled_domains");
            boolean sessionsEnabledForDomain = disabledSessionDomains.stream().noneMatch(d -> domain.contains(d.toLowerCase()));

            // skip dialog if session enabled & there is fresh session
            if (sessionsEnabledForDomain && ip.equals(data.lastIp())) {
                long diff = System.currentTimeMillis() - data.lastLogin().getTime();
                if (diff < TimeUnit.MINUTES.toMillis(plugin.getConfig().getLong("session.duration_minutes", 60))) {
                    plugin.getDb().updateSession(data.uuid(), ip, new Timestamp(System.currentTimeMillis()));
                    return;
                }
            }

            //showing auth dialog if data!=null, and there is no fresh session
            showAuthDialog(connection, data, ip, domain);
        } else {
            //showing register dialog if there is no data
            showRegisterDialog(connection, data, ip, domain);
        }
    }

    private void showAuthDialog(PlayerConfigurationConnection connection, DatabaseManager.PlayerData data, String ip, String domain) {
        // defining config values
        String title = plugin.getConfig().getString("messages.login_title");
        String bodyText = plugin.getConfig().getString("messages.login_body");
        String passLabel = plugin.getConfig().getString("messages.pass_label");
        String loginButtonLabel = plugin.getConfig().getString("messages.loginbutton_label");
        int loginButtonWidth = plugin.getConfig().getInt("messages.loginbutton_width");

        // building dialog
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(mm.deserialize(title))
                        .canCloseWithEscape(false)
                        .body(List.of(DialogBody.plainMessage(mm.deserialize(bodyText))))
                        .inputs(List.of(DialogInput.text("password", Component.text(passLabel))
                                .maxLength(64).build()))
                        .build())
                .type(DialogType.multiAction(List.of(
                        ActionButton.create(Component.text(loginButtonLabel), null, loginButtonWidth, DialogAction.customClick(LOGIN_KEY, null))
                )).build())
        );

        handleDialogSession(connection, dialog, ip, domain);
    }

    private void showRegisterDialog(PlayerConfigurationConnection connection, DatabaseManager.PlayerData data, String ip, String domain) {
        // defining config values
        String title = plugin.getConfig().getString("messages.register_title");
        String bodyText = plugin.getConfig().getString("messages.register_body");
        String passLabel = plugin.getConfig().getString("messages.pass_label");
        String confirmPassLabel = plugin.getConfig().getString("messages.confirmpass_label");
        String registerButtonLabel = plugin.getConfig().getString("messages.registerbutton_label");
        int registerButtonWidth = plugin.getConfig().getInt("messages.registerbutton_width");

        // building dialog
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(mm.deserialize(title))
                        .canCloseWithEscape(false)
                        .body(List.of(DialogBody.plainMessage(mm.deserialize(bodyText))))
                        .inputs(List.of(
                                DialogInput.text("password", Component.text(passLabel, NamedTextColor.GRAY)).maxLength(64).width(200).build(),
                                DialogInput.text("password_confirm", Component.text(confirmPassLabel, NamedTextColor.GRAY)).maxLength(64).width(200).build()
                        )).build())
                .type(DialogType.multiAction(List.of(
                        ActionButton.create(Component.text(registerButtonLabel), null, registerButtonWidth, DialogAction.customClick(REGISTER_KEY, null))
                )).build())
        );

        handleDialogSession(connection, dialog, ip, domain);
    }

    private void handleDialogSession(PlayerConfigurationConnection connection, Dialog dialog, String ip, String domain) {
        Audience audience = connection.getAudience();
        UUID uuid = connection.getProfile().getId();

        CompletableFuture<AuthResult> future = new CompletableFuture<>();
        awaiting.put(uuid, future);

        // forcing to close dialog after timeout
        int timeoutSeconds = plugin.getConfig().getInt("session.timeout_seconds", 60);
        future.completeOnTimeout(AuthResult.TIMEOUT, timeoutSeconds, TimeUnit.SECONDS);

        audience.showDialog(dialog);

        AuthResult result = future.join();
        awaiting.remove(uuid);

        awaiting.remove(uuid);
        loginIps.remove(uuid);

        audience.closeDialog();

        switch (result) {
            case SUCCESS:
                // forgiving ip address if there was successful login
                failedAttempts.remove(ip);
                break;
            case TIMEOUT:
                // kick player on timeout
                connection.disconnect(mm.deserialize(plugin.getConfig().getString("messages.timeout")));
                break;
            case CANCEL:
                // kick if he didnt type any password in input
                connection.disconnect(mm.deserialize(plugin.getConfig().getString("messages.missing_password")));
                break;
            case MISSMATCH:
                // kick if passwords dont match (on register)
                connection.disconnect(mm.deserialize(plugin.getConfig().getString("messages.mismatch_password")));
                break;
            case TOO_SHORT:
                // kick if password is too short (on register)
                int minLength = plugin.getConfig().getInt("security.min_length", 6);
                String shortMsg = plugin.getConfig().getString("messages.password_too_short")
                        .replace("{min}", String.valueOf(minLength));
                connection.disconnect(mm.deserialize(shortMsg));
                break;
            case WRONG_PASSWORD:
                // kick if password wrong (on login) and remembering it :D
                handleFailedAttempt(ip, domain);
                connection.disconnect(mm.deserialize(plugin.getConfig().getString("messages.wrong_password")));
                break;
        }
    }

    @EventHandler
    public void onClick(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerConfigurationConnection cfg)) return;

        // making sure that its OUR event
        Key id = event.getIdentifier();
        if (!id.equals(LOGIN_KEY) && !id.equals(REGISTER_KEY)) return;

        // get player data
        UUID uuid = cfg.getProfile().getId();
        String username = cfg.getProfile().getName();
        String ip = loginIps.getOrDefault(uuid, ((java.net.InetSocketAddress) cfg.getAddress()).getAddress().getHostAddress());

        CompletableFuture<AuthResult> future = awaiting.get(uuid);
        if (future == null || future.isDone()) return;

        // get password value from input
        DialogResponseView view = event.getDialogResponseView();
        String password = view.getText("password");

        if (password == null || password.isEmpty() || password.length() > 64) {
            future.complete(AuthResult.CANCEL);
            return;
        }

        // get min_length for password and bcrypt rounds
        int rounds = plugin.getConfig().getInt("security.bcrypt_rounds", 10);
        int minLength = plugin.getConfig().getInt("security.min_length", 6);

        CompletableFuture.runAsync(() -> {
            if (id.equals(REGISTER_KEY)) {
                // checking password length
                if (password.length() < minLength) {
                    future.complete(AuthResult.TOO_SHORT);
                    return;
                }

                // making sure both passwords match
                String password_confirm = view.getText("password_confirm");
                if (password_confirm == null || !password.equals(password_confirm)) {
                    future.complete(AuthResult.MISSMATCH);
                    return;
                }

                // hash-hash-hashing
                String hash = BCrypt.hashpw(password, BCrypt.gensalt(rounds));

                // updating table
                DatabaseManager.PlayerData newData = new DatabaseManager.PlayerData(
                        uuid.toString(), username, hash, ip,
                        new Timestamp(System.currentTimeMillis()), false);
                plugin.getDb().savePlayer(newData).join();

                // return success
                future.complete(AuthResult.SUCCESS);

            } else if (id.equals(LOGIN_KEY)) {

                // geting player data from db
                DatabaseManager.PlayerData data = plugin.getDb().getPlayerData(username).join();

                // checking hash-hash-hashed password
                if (data != null && BCrypt.checkpw(password, data.passwordHash())) {
                    plugin.getDb().updateSession(data.uuid(), ip, new Timestamp(System.currentTimeMillis()));
                    // return success
                    future.complete(AuthResult.SUCCESS);
                } else {
                    // return wrong password
                    future.complete(AuthResult.WRONG_PASSWORD);
                }
            }
        }, plugin.getAsyncExecutor());
    }

    private void handleFailedAttempt(String ip, String domain) {

        // is domain in "whitelist"?
        List<String> noBanDomains = plugin.getConfig().getStringList("security.no_ban_domains");
        if (noBanDomains.stream().anyMatch(d -> domain.contains(d.toLowerCase()))) {
            return;
        }

        int maxAttempts = plugin.getConfig().getInt("security.max_attempts", 3);
        int banMinutes = plugin.getConfig().getInt("security.ban_minutes", 15);

        int attempts = failedAttempts.computeIfAbsent(ip, k -> new AtomicInteger(0)).incrementAndGet();
        if (attempts >= maxAttempts) {
            bannedIps.put(ip, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(banMinutes));
            failedAttempts.remove(ip);
        }
    }

    private boolean isIpBanned(String ip) {
        Long unbanTime = bannedIps.get(ip);
        if (unbanTime != null) {
            if (System.currentTimeMillis() > unbanTime) {
                // forgeting banned ip if time expired
                bannedIps.remove(ip);
                return false;
            }
            return true;
        }
        return false;
    }
}