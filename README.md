# housegraph-nodes

Node libraries for [HouseGraph](https://github.com/jaymcole/HouseGraph). Each subproject is an
independent library from HouseGraph's point of view — its own id, its own manifest, its own jar —
they just share a build and a release.

| Library | Nodes | Bundles |
| --- | --- | --- |
| `housegraph-iot` | Squirrel Alarm (Arduino LED-matrix sign) | nothing |

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

- **Never `implementation` the API.** The host supplies `housegraph-api` and its transitive
  `org.json` / `slf4j-api`. Bundling the api gives your library its own `BaseNode`, so every node
  in it fails the host's `isAssignableFrom` check during discovery and **never appears**, with
  nothing in the log to explain why. Bundling `slf4j-api` gives a second logging binding with no
  outputs attached, so all your logging silently vanishes. HouseGraph's installer rejects a jar
  containing either. The convention plugin already gets this right — don't override it.
- **Always `@Node.Type`, prefixed with your library id.** It pins the id your node is written
  under in save files. Without it, renaming or moving the class strands every saved graph using it.
- **A node's static initializer runs at first instantiation, not at discovery** — the host loads
  classes with `initialize = false`. So a `ValueEditors.register(...)` in a static block only takes
  effect once one of your nodes exists.
- **`onExecuted()` reaches you on the JavaFX thread**, so your UI code needs no `Platform.runLater`.
  Work *you* start does.

## Releasing

```bash
git tag v0.1.0 && git push --tags
```

The workflow builds and tests everything, then attaches each library's `-all.jar` to the release.

## License

MIT — see [LICENSE](LICENSE).
