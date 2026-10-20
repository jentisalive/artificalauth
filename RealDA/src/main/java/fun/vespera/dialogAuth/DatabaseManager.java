package fun.vespera.dialogAuth;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class DatabaseManager {
    private final HikariDataSource dataSource;
    private final ExecutorService executor;
    private final String dbType;

    public DatabaseManager(String type, String host, int port, String database, String username, String password, String dataFolder, ExecutorService executor) {
        this.executor = executor;
        this.dbType = type.toLowerCase();

        HikariConfig config = new HikariConfig();

        // dynamic DB management
        switch (dbType) {
            case "postgresql":
                config.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
                config.setUsername(username);
                config.setPassword(password);
                config.setDriverClassName("org.postgresql.Driver");
                break;
            case "mysql":
                config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database);
                config.setUsername(username);
                config.setPassword(password);
                config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                break;
            // if picked db is h2, or there is no picked db - using h2 by default
            case "h2":
            default:
                config.setJdbcUrl("jdbc:h2:" + dataFolder + "/" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
                config.setUsername(username.isEmpty() ? "sa" : username);
                config.setPassword(password.isEmpty() ? "" : password);
                config.setDriverClassName("org.h2.Driver");
                break;
        }

        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        dataSource = new HikariDataSource(config);
        createTables();
    }

    // creating tables if they dont exist
    private void createTables() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS players (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "username VARCHAR(16) NOT NULL, " +
                    "password_hash VARCHAR(255), " +
                    "last_ip VARCHAR(45), " +
                    "last_login TIMESTAMP, " +
                    "is_premium BOOLEAN DEFAULT FALSE)");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // closing database connection pool
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // getting player data by nickname
    public CompletableFuture<PlayerData> getPlayerData(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT * FROM players WHERE username = ?")) {
                stmt.setString(1, username);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new PlayerData(
                                rs.getString("uuid"),
                                rs.getString("username"),
                                rs.getString("password_hash"),
                                rs.getString("last_ip"),
                                rs.getTimestamp("last_login"),
                                rs.getBoolean("is_premium")
                        );
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return null;
        }, executor);
    }

    // saving new player data when register
    public CompletableFuture<Void> savePlayer(PlayerData data) {
        return CompletableFuture.runAsync(() -> {
            String sql;
            // for PostgreSQL
            if (dbType.equals("postgresql")) {
                sql = "INSERT INTO players (uuid, username, password_hash, last_ip, last_login, is_premium) " +
                        "VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT (uuid) DO UPDATE SET " +
                        "password_hash=EXCLUDED.password_hash, last_ip=EXCLUDED.last_ip, " +
                        "last_login=EXCLUDED.last_login, is_premium=EXCLUDED.is_premium";
            } else {
                // for H2 & MySQL
                sql = "INSERT INTO players (uuid, username, password_hash, last_ip, last_login, is_premium) " +
                        "VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE " +
                        "password_hash=VALUES(password_hash), last_ip=VALUES(last_ip), " +
                        "last_login=VALUES(last_login), is_premium=VALUES(is_premium)";
            }

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, data.uuid());
                stmt.setString(2, data.username());
                stmt.setString(3, data.passwordHash());
                stmt.setString(4, data.lastIp());
                stmt.setTimestamp(5, data.lastLogin());
                stmt.setBoolean(6, data.isPremium());
                stmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }, executor);
    }

    // updating player`s last_ip and last_login(time) for update his session
    public CompletableFuture<Void> updateSession(String uuid, String ip, Timestamp lastLogin) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE players SET last_ip = ?, last_login = ? WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, ip);
                stmt.setTimestamp(2, lastLogin);
                stmt.setString(3, uuid);
                stmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }, executor);
    }

    // forcing to delete player from db (used for /forceunreg)
    public CompletableFuture<Void> deletePlayer(String username) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM players WHERE username = ?")) {
                stmt.setString(1, username);
                stmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }, executor);
    }

    public record PlayerData(String uuid, String username, String passwordHash, String lastIp, Timestamp lastLogin, boolean isPremium) {}
}