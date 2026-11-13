# Contributing

Thanks for your interest in EXeaEntityCore. The project is proprietary (see [LICENSE](LICENSE)). These
rules follow from that.

## Issues

Bug reports and feature requests are welcome in [GitHub Issues](https://github.com/asnise/EXeaEntityCore/issues).
Use the templates. For a bug, include:
- the EXea version and the Paper build
- the model files (or a small `.bbmodel` that shows the problem)
- what you expected
- what happened

## Pull requests

Open an issue first, so we can agree on the change before you spend time on it. By submitting a pull
request, you agree that the copyright holder may use, change and distribute your contribution under
any terms, including the terms of this project's license.

Code style:
- Java 25, 4-space indent, no wildcard imports.
- Comments explain *why*, in plain English.
- Anything other plugins need goes in `com.exea.entity.api`. Keep `core` internal.
- Bukkit calls run on the main thread.
- Check before you open the PR:
  - `./gradlew jar` builds.
  - You tested in game on Paper 26.2 (spawn, animations, `/exea reload`).
  - You updated `CHANGELOG.md` under *Unreleased*.
