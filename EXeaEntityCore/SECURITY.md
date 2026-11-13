# Security policy

## Supported versions

Only the latest release gets security fixes.

| Version | Supported |
|---|---|
| 1.1.x | ✅ |
| < 1.1 | ❌ |

## Reporting a vulnerability

Do **not** open a public issue for a security problem. Report it privately through
[GitHub Security Advisories](https://github.com/asnise/EXeaEntityCore/security/advisories/new).

Include the following:
- the EXea version and the Paper build
- what an attacker can do
- steps to reproduce

You should get a reply within 7 days.

## Attack surface

- **Resource pack HTTP server.** It listens on `resource_pack.port`, 8086 by default, and serves only
  the generated pack zip. Put it behind your firewall or reverse proxy as you would any public port.
  If you do not need it, set `resource_pack.enabled: false`.
- **Model files.** These are `models/*.json` and `pack/` in the plugin folder. They are trusted input:
  only server admins should be able to write them.
- **Commands.** Every `/exea` command needs `exea.admin`.
