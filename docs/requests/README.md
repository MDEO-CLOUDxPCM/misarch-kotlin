# Requests to the foundation agent

Port agents: put change requests for shared parts (`libs/common`, `build-logic`, `gradle/libs.versions.toml`,
`tools/`, `deploy/`) into `docs/requests/<service>.md` (what, why, proposed API). Small backwards-compatible
additions to `libs/common` may be made directly (then run `tools/build.sh :libs:common:test :services:address:test`).
