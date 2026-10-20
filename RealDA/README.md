# DialogAuth

An authentication system that utilizes the Dialogs feature (added in Minecraft 1.21.6) to provide a modern, user-friendly UI for players.

**check also:** [DialogAuthVelocity](https://github.com/MiFo4ka/DialogAuthVelocity) - optinional addon for `/license` work

## Features
* **Multi-Database Support:** MySQL, H2, and PostgreSQL.
* **Customizable Session System:** Keep players logged in securely.
* **Domain-Specific Session Disabling:** Useful if you use proxies/protection like TCPShield, NeoProtect, etc.
* **Anti-Brute Force System:** Protects player accounts from password guessing.
* **Domain-Specific Rate Limiting:** Easily bypass or adjust rate limits for trusted connections.
* **Premium Account Support:** Seamless integration via an add-on.
* **Secure Hashing:** Uses bcrypt for safe and secure password storage.
* **Fully Configurable:** Easily customize messages, UI text, and other settings via `config.yml`.

# Demo


https://github.com/user-attachments/assets/8b3f127c-bfe4-49b1-be10-dfc9c65f2f42


You can also test it on my Vanilla-like server:
`play.vespera.fun`
`alt.vespera.fun`


## Commands
* `/changepass` - Opens the Dialog UI to change your password *(Player)*
* `/license` - Links your premium account *(Player)* 

* `/forceunreg <player>` - Forcefully deletes a player's account *(Admin)*
* `/forcechangepass <player> <new_password>` - Forcefully changes a player's password *(Admin)*
* `/dauth reload` - Reloads the plugin configuration *(Admin)*

## Requirements 
* **Minecraft Version:** 1.21.6 or higher.
* **Java Version:** Java 21 

## License
DialogAuth is licensed under the **GNU GPLv3** license. See the [LICENSE](LICENSE) file for more details.
