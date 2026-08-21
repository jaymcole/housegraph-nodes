# housegraph-nodes

Node libraries for [HouseGraph](https://github.com/jaymcole/HouseGraph). Each subproject is an
independent library from HouseGraph's point of view — its own id, its own manifest, its own jar —
they just share a build and a release.

| Library | Nodes | Bundles |
| --- | --- | --- |
| `housegraph-squirrel` | Squirrel Alarm (Arduino LED-matrix sign) | nothing |
| `housegraph-discord` | Discord Bot, Command, Slash Command, Reply, Send Message, Send Buttons, Button Click | JDA |
| `housegraph-reolink` | Discover Cameras, Camera Motion Status, Camera Snapshot | nothing |
| `housegraph-web` | Web Server, Node Server, Web Hook, Web Hook Request, Web Hook Reply | jmdns |
| `housegraph-ml` | Animal Classifier | Deep Java Library (PyTorch) |
| `housegraph-github` | Git Sync | JGit |
| `housegraph-experimental` | Lightbulb | nothing |
| `housegraph-filesystem` | Create Folder | nothing |
| `housegraph-schedule` | Daily Trigger | nothing |

## Installing

In HouseGraph: **Node Libraries… → Add from URL…**, paste this repository's URL. A release
publishes several libraries, so you'll be asked which one.

## Why one repository

The API will change. When `housegraph-api` goes 0.3, every library needs rebuilding and
re-releasing — that's one commit and one tag here, against one pull request and one tag per
repository otherwise. The build rules are also easy to get subtly wrong in ways that fail
*silently* (a node that never appears, logging that vanishes into nowhere, two libraries fighting
over a bundled dependency), so they're written once in `buildSrc` rather than copied per library
and left to drift.

The trade is **lockstep versioning**: releasing one library bumps every library's version number.

Someone writing a library of their own should start from
[housegraph-plugin-template](https://github.com/jaymcole/housegraph-plugin-template) instead —
one repository, one library, independently versioned.

## Adding a library here

Before designing a node's ports, see [`CLAUDE.md`](CLAUDE.md#node-design-control-vs-action) — in
short, a node should almost always be either control-oriented (a trigger/timer/branch/loop) or
action-oriented (does a thing, reports the outcome), not both.

```
settings.gradle              include 'housegraph-yourthing'
housegraph-yourthing/
  build.gradle               ~10 lines: apply the convention plugin, declare identity
  src/main/java/io/github/jaymcole/housegraph/plugins/yourthing/nodes/
```

```groovy
plugins { id 'housegraph-node-library' }

nodeLibrary {
    id = 'housegraph-yourthing'
    libraryName = 'Your Thing'
    description = 'What it does.'
    nodePackages = ['io.github.jaymcole.housegraph.plugins.yourthing.nodes']
}

dependencies {
    // Anything here IS bundled into the shaded jar and MUST be relocated — all installed
    // libraries share one class loader.
    // implementation 'com.example:whatever:1.0'
}
```

Then in `shadowJar`, add a `relocate` line for each bundled dependency.

The convention plugin (`buildSrc/src/main/groovy/housegraph-node-library.gradle`) handles the
rest: `compileOnly` on the API, the JavaFX setup, the generated manifest, and the shaded jar named
`<pluginId>-<version>-all.jar` — which is how HouseGraph matches a library to its own jar when one
release carries several.

## Rules worth knowing before they bite

**→ [`docs/shared/node-library-rules.md`](docs/shared/node-library-rules.md)** — the build
rules, the API surface, node design, and the trust note. Every rule there has a *silent* failure
mode: a node that never appears, logging that vanishes, a saved graph that can't find its nodes.

That file is maintained in
[HouseGraph](https://github.com/jaymcole/HouseGraph/blob/main/docs/shared/node-library-rules.md)
and synced here, so all three repositories teach the same thing. **Don't edit the copy in this
repository** — the next sync overwrites it.

The convention plugin already implements every build rule on that page. Don't override it.

## Building

To build every library's installable jar locally, without releasing anything:

```bash
./gradlew shadedJars
```

Each library's `<pluginId>-<version>-all.jar` lands under its own `build/libs/` (e.g.
`housegraph-web/build/libs/housegraph-web-0.1.0-all.jar`). Pass `-Pversion=X.Y.Z` to control the
version stamp; without it, every library falls back to `0.1.0` (see the root `build.gradle`).
`./gradlew build` does the same plus runs every library's tests.

## Releasing

Releasing is automatic: merging a pull request into `main` triggers
`.github/workflows/auto-tag.yml`, which tags the resulting commit `v<major>.<minor>.<patch>`,
bumping the patch number by default. Put `#minor` or `#major` anywhere in the PR title to bump
one of those instead (and reset the parts below it to zero) — e.g. a title of `Add Foo node
#minor` bumps the minor version.

Pushing a `v*` tag — whether from `auto-tag.yml` or manually — triggers
`.github/workflows/release.yml`, which:

1. Checks out, sets up JDK 21.
2. Runs `./gradlew build -Pversion=<tag without the v>` — builds and tests every library at that
   version.
3. Attaches every library's `*-all.jar` to a GitHub Release, with auto-generated release notes.

Because versioning is lockstep (see above), one tag releases every library at that version
number, even if only one of them actually changed.

**The jar naming is load-bearing, not cosmetic.** A release carries one jar per library;
HouseGraph's installer matches `<pluginId>-<version>-all.jar` to pick the right one. Don't rename
these manually, and don't hand-edit `shadowJar { archiveBaseName = ... }` in a library's
`build.gradle` — it's already correct via the convention plugin.

A docs sync from HouseGraph does **not** cut a release: `auto-tag.yml` ignores
`docs/shared/**`.

## License

MIT — see [LICENSE](LICENSE).
