# DialogAuthVelocity

Addon for /license (premium) command that allows to connect premium players with online-mode without typing password

**check also:** [DialogAuth](https://github.com/MiFo4ka/DialogAuth) - required

## Features
* **Multi-Database Support:** MySQL, H2, and PostgreSQL.
* **Customizable Session System:** Keep players logged in securely.
* **Domain-Specific Session Disabling:** Useful if you use proxies/protection like TCPShield, NeoProtect, etc.
* **Anti-Brute Force System:** Protects player accounts from password guessing.
* **Domain-Specific Rate Limiting:** Easily bypass or adjust rate limits for trusted connections.
* **Premium Account Support:** Seamless integration via an add-on.
* **Secure Hashing:** Uses bcrypt for safe and secure password storage.
* **Fully Configurable:** Easily customize messages, UI text, and other settings via `config.yml`.

## Demo

https://github.com/user-attachments/assets/b53027c0-6839-457a-aff7-b82c6e5975e2

## Installation Guide
1. **Install the Plugins:** Drop both `DialogAuth` (for Paper) and `DialogAuthVelocity` (for Velocity) into their respective `plugins` folders.
2. **Enable the API:** Open the `config.yml` file of the Paper plugin and ensure that the API option is set to `true`.
3. **Configure the Paper Plugin:** Set the `host` and `port` in the Paper plugin configuration (the default is `0.0.0.0:8080`).
4. **Configure the Velocity Plugin:** Set the matching `host` and `port` in the Velocity plugin configuration. 
   > *Note: If your Velocity proxy is hosted on a different server/machine, make sure to specify the public IP address of your Paper server.*
5. **Verify the Setup:** Start (or restart) both servers and ensure that the connection between the Velocity and Paper plugins is successfully established.

## License
DialogAuthVelocity is licensed under the **GNU GPLv3** license. See the [LICENSE](LICENSE) file for more details.
