# housegraph-nodes

Node libraries for [HouseGraph](https://github.com/jaymcole/HouseGraph). Each subproject is an
independent library from HouseGraph's point of view — its own id, its own manifest, its own jar —
they just share a build and a release.

| Library | Nodes | Type-id prefix | Bundles |
| --- | --- | --- | --- |
| `housegraph-squirrel` | Squirrel Alarm (Arduino LED-matrix sign) | `iot.` | nothing |
| `housegraph-discord` | Discord Bot, Command, Slash Command, Reply, Send Message | `discord.` | JDA |
| `housegraph-reolink` | Discover Cameras, Camera Motion Status, Camera Snapshot | `camera.` | nothing |
| `housegraph-web` | Web Server, Node Server | `web.` | jmdns |
| `housegraph-ml` | Animal Classifier | `ml.` | Deep Java Library (PyTorch) |
| `housegraph-github` | Git Sync | `github.` | JGit |
| `housegraph-experimental` | Lightbulb | `experimental.` | nothing |
| `housegraph-filesystem` | Create Folder | `filesystem.` | nothing |

The type-id prefix is the `@Node.Type` namespace each library writes into save files — see
[Conventions this repository holds](#conventions-this-repository-holds). It is not always the
library name: `housegraph-squirrel` and `housegraph-reolink` keep the `iot.`/`camera.` prefixes
their nodes had while they still lived inside HouseGraph, so old saves keep resolving.

## Installing

In HouseGraph: **Node Libraries… → Add from URL…**, paste this repository's URL. A release
publishes several libraries, so you'll be asked which one.

## Writing a node: the reference docs live in HouseGraph

**This repository documents itself — the build, the release, and the conventions these eight
libraries hold. How a HouseGraph node actually works is documented in
[HouseGraph](https://github.com/jaymcole/HouseGraph) and is deliberately not restated here.**
Anything true of *every* node library (the port model, the api contract, the class loader, the
sdk extension points) is one copy over there, not a second copy here that drifts out of date.

| To understand… | Read |
| --- | --- |
| The whole picture, and the standards a node is expected to hold | [`CLAUDE.md`](https://github.com/jaymcole/HouseGraph/blob/main/CLAUDE.md) · [`overview.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/overview.md) |
| Ports, `NodeVariable`, what gets persisted, branches, joins, loops | [`nodes.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/nodes.md) |
| The step-by-step add-a-node recipe and a skeleton to copy | [`nodes.md#recipe-add-a-new-node`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/nodes.md#recipe-add-a-new-node) · [`graph/nodes/CLAUDE.md`](https://github.com/jaymcole/HouseGraph/blob/main/app/src/main/java/io/github/jaymcole/housegraph/graph/nodes/CLAUDE.md) |
| Why the library must be `compileOnly` on the api, how the jar is loaded, why one class loader | [`plugins.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/plugins.md) |
| Data-pull vs. flow-push, threading, execution policy, bounded teardown | [`graph-engine.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/graph-engine.md) |
| Inline node UI, `AutoStartable`, custom value editors, the save format | [`ui.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/ui.md) |
| Long-lived connections registered by name (a bot, a server) | [`resources.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/resources.md) |
| Where files go, and how to hold a credential | [`storage-and-secrets.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/storage-and-secrets.md) |
| Where a log line ends up | [`logging.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/logging.md) |
| Test patterns the node tests here follow | [`testing.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/testing.md) |
| Per-library design notes for the integrations in this repo, and the lessons each extraction surfaced | [`integrations.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/integrations.md) |

Writing a library of your own, outside this repository? Start from
[housegraph-plugin-template](https://github.com/jaymcole/housegraph-plugin-template) — one
repository, one library, independently versioned.

## Why one repository

The API will change. When `housegraph-api` goes 0.4, every library needs rebuilding and
re-releasing — that's one commit and one tag here, against one pull request and one tag per
repository otherwise. The build rules are also easy to get subtly wrong in ways that fail
*silently* (a node that never appears, logging that vanishes into nowhere, two libraries fighting
over a bundled dependency), so they're written once in `buildSrc` rather than copied per library
and left to drift.

The trade is **lockstep versioning**: releasing one library bumps every library's version number.

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
    // Anything here IS bundled into the shaded jar — see the bundling rules below.
    // implementation 'com.example:whatever:1.0'
}
```

The convention plugin (`buildSrc/src/main/groovy/housegraph-node-library.gradle`) handles the
rest: `compileOnly` on the API, the JavaFX setup, the generated manifest, and the shaded jar named
`<pluginId>-<version>-all.jar` — which is how HouseGraph matches a library to its own jar when one
release carries several. It already gets HouseGraph's hard requirements right (see
[`plugins.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/plugins.md#consuming-housegraph-api));
don't override them per library.

## Conventions this repository holds

These are choices made *here*, on top of what HouseGraph requires of any node library.

- **`@Node.Type("<prefix>.<ClassName>")` on every node, using the prefix in the table above.**
  HouseGraph defaults a node's save-file id to its bare class name, which collides easily across
  independently-written libraries; every node here namespaces its id instead. Renaming a class
  without keeping the old id strands every saved graph using it — moving it between packages is
  safe, the id is what matters. See
  [`nodes.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/nodes.md).
- **Exclude `slf4j-api` from every bundled dependency that drags it in.** JDA, JGit, DJL and jmdns
  all depend on it; left alone it lands in the shaded jar, which the host's installer rejects. Each
  `build.gradle` carries the `exclude group: 'org.slf4j', module: 'slf4j-api'` and a comment saying
  why.
- **Relocate a bundled dependency, or write down why you didn't.** All installed libraries share
  one class loader, so two of them bundling different versions of the same thing would fight. In
  practice none of the four bundling libraries here relocate: each is currently the only library
  bundling that dependency, and JDA/JGit/DJL are reflective and `ServiceLoader`-driven enough that
  blind relocation is a real risk for no present benefit. That is a deliberate, commented opt-out
  per library, not the default — and `mergeServiceFiles()` is still required for any dependency
  using `ServiceLoader`.
- **Every library has tests, and they run headless.** Follow the existing per-library
  `src/test/java` layout and the patterns in
  [`testing.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/testing.md).
- **Bump the API in one place.** `houseGraphApi` in `gradle.properties` is the single coordinate
  every library compiles against; the manifest's `apiVersion` is derived from it, so the two
  cannot disagree.

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

## `docs/shared/` is not written here

If a `docs/shared/` folder appears in this repository, it is a mirror: HouseGraph's
[`sync-docs.yml`](https://github.com/jaymcole/HouseGraph/blob/main/.github/workflows/sync-docs.yml)
copies its own `docs/shared/` into this repo on every push to `main` that touches it, opening a
`docs-sync` pull request and merging it automatically. Edits made here are overwritten on the next
sync — change the file in HouseGraph instead. See
[`doc-sync.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/doc-sync.md).

## License

MIT — see [LICENSE](LICENSE).
