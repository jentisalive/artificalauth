# Third-party material

This repository preserves the local AuthPlugin workspace, including separate reference projects and dependency artifacts. It does not claim authorship of those projects or replace their licenses.

## DialogAuthVelocity and DialogAuth

- `DialogAuth/`: DialogAuthVelocity, upstream https://github.com/MiFo4ka/DialogAuthVelocity
- `RealDA/`: DialogAuth, upstream https://github.com/MiFo4ka/DialogAuth
- Their original README files, copyright notices, and GPLv3 license files are preserved in those directories.
- These are separate source trees, not required runtime modules of ArtificalAuth.

## LimboAPI

- Upstream: https://github.com/Elytrium/LimboAPI
- Bundled build: `1.1.27-SNAPSHOT`, Modrinth version `zOTyppCs`, commit abbreviation `bfef579`.
- Build metadata and original download URL: [`lib/limboapi-source.json`](lib/limboapi-source.json).
- Corresponding upstream source: https://github.com/Elytrium/LimboAPI/tree/bfef579
- License: GNU Affero General Public License v3; [included license text](licenses/LimboAPI-AGPL-3.0.txt), https://github.com/Elytrium/LimboAPI/blob/bfef579/LICENSE
- The unmodified dependency jar is included in `lib/` and `dist/` for the original workspace build/install process.

## SnakeYAML

- ArtificalAuth jars bundle SnakeYAML; the original build script packages the dependency from the local Minecraft libraries directory.
- Upstream: https://bitbucket.org/snakeyaml/snakeyaml
- Source for the version used by the local build (2.2): https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/2.2/snakeyaml-2.2-sources.jar
- License: Apache License 2.0, [included license text](licenses/Apache-2.0.txt), https://www.apache.org/licenses/LICENSE-2.0

Velocity, Adventure, Gson and LimboAPI APIs are used during compilation/runtime. The complete Velocity/Minecraft server distributions are not uploaded here. The Minecraft protocol description used by the integration test is preserved as part of the local test resources; its upstream provenance should be verified before redistributing it separately.
