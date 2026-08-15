# CLAUDE.md — housegraph-nodes

Read [`README.md`](README.md) first for how this repository is put together (one library per
subproject, the convention plugin, the conventions these libraries hold, releasing). This file is
contributor/agent guidance — things to get right when adding or changing a node — not build
mechanics.

## Read HouseGraph's docs before writing a node

**Nothing about how a HouseGraph node works is documented in this repository.** It is all in
[HouseGraph](https://github.com/jaymcole/HouseGraph), one copy, kept current by that repo's own
documentation mandate. Read what you need from there before writing code; a node written from
guesswork about the port model or the class loader fails in ways that produce no error message
(a node that never appears in the Add-Node menu, logging that vanishes, a value silently not
persisted).

| Before you… | Read |
| --- | --- |
| Write any node at all | [`CLAUDE.md`](https://github.com/jaymcole/HouseGraph/blob/main/CLAUDE.md) (the map and the core standards) and [`nodes.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/nodes.md) (ports, `NodeVariable`, persistence rules, branches, joins, loops) |
| Copy a working skeleton | [`nodes.md#recipe-add-a-new-node`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/nodes.md#recipe-add-a-new-node) and [`graph/nodes/CLAUDE.md`](https://github.com/jaymcole/HouseGraph/blob/main/app/src/main/java/io/github/jaymcole/housegraph/graph/nodes/CLAUDE.md) |
| Touch a `build.gradle`, or wonder what may be bundled | [`plugins.md#consuming-housegraph-api`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/plugins.md#consuming-housegraph-api) — `compileOnly` on the api is a hard requirement with a silent failure mode; [`plugins.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/plugins.md) in full for the shared parent-first class loader, the manifest, and install-time validation |
| Reason about when a node runs, or run work on a thread | [`graph-engine.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/graph-engine.md) — data is pulled, flow is pushed and fire-and-forget; [`#execution-policy-re-entrant-triggers`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/graph-engine.md#execution-policy-re-entrant-triggers) for re-entrancy |
| Write teardown that waits on the outside world | [`graph-engine.md#teardown-is-two-halves-and-the-slow-one-is-bounded`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/graph-engine.md#teardown-is-two-halves-and-the-slow-one-is-bounded) — the slow half goes in `releaseResources()`, not `onRemoved()` |
| Give a node inline UI, resume it on load, or make a type editable | [`ui.md#node-inline-ui-sdknodecontentprovider`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/ui.md#node-inline-ui-sdknodecontentprovider), [`#resuming-running-nodes-on-load-sdkautostartable`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/ui.md#resuming-running-nodes-on-load-sdkautostartable), [`#inline-value-editing-sdkvalueeditors`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/ui.md#inline-value-editing-sdkvalueeditors) |
| Manage a long-lived connection (a bot, a server, a socket) | [`resources.md#the-resource-node-pattern`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/resources.md#the-resource-node-pattern) |
| Handle a credential, or write a file | [`storage-and-secrets.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/storage-and-secrets.md) — secrets live in `SecretsStore` and a node persists the *key*; paths come from `AppDirectories` |
| Add a log line | [`logging.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/logging.md) |
| Write tests | [`testing.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/testing.md) |
| Change one of the extracted integrations (Discord, camera, web, ml, iot) | [`integrations.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/integrations.md) — the per-integration design notes and extraction lessons still live there |

**Keep it that way.** If something you are about to add to this file or to `README.md` would be
equally true in `housegraph-plugin-template` or in any other node library anyone might write, it
belongs in HouseGraph's docs — link it from here instead of restating it. What stays here is what
is only true of *this* repository: its layout, its build and release, and the conventions below.

## Node design: control vs. action

A node should almost always be **either** control-oriented **or** action-oriented, not both.

- **Control-oriented** nodes exist to shape *when* and *how often* flow moves: a trigger, a
  timer, a branch, a loop, a join. Their job is deciding whether/when something downstream runs —
  not doing that something themselves. (HouseGraph's built-in library ships several of these — a
  plain trigger button, a repeating timer trigger, an `If`, a `ForEach` — so a library here rarely
  needs to reinvent one.)
- **Action-oriented** nodes exist to *do* something: call an API, read a sensor, write a file,
  transform data. Their flow ports exist only to report that they ran and, at most, which of a
  small number of known outcomes happened for *that one run* — not to decide independently when
  to run again.

**Why this split matters, concretely:** `housegraph-github`'s `GitSyncNode` originally owned
both — its own `Start`/`Stop` timer *and* the git sync itself, in one class. That made it
impossible to reuse with a different schedule, harder to test (the timer and the action were
welded together), and duplicated what a repeating-trigger node already does. Splitting the timer
out — the node now has a flow-in and expects something else to drive it — left it a plain action
node: `Checked` always fires (it ran), `Pulled` fires only when that run's outcome was "found and
pulled a new commit." That's the shape to reach for by default: an action node's branches
describe the outcomes of one invocation, not points on a schedule it manages itself.

**When a request describes a node that would own its own scheduling/looping/branching-on-a-timer
*and* perform an external action, don't build the fused version by default — ask whether the
control part (the trigger/timer/loop) and the action part should be two composable nodes
instead**, with the control node's flow-out wired into the action node's flow-in.

The one common exception is a *resource* node that must own a real connection lifecycle (a
Discord bot, a web server) — see `AutoStartable` and `NodeContentProvider` in `housegraph-api`,
and [`resources.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/resources.md).
There, "control" (Start/Stop) and "state" genuinely belong to the same node because the
connection itself is what's being managed. Treat that as a deliberate, named exception, not
precedent for fusing scheduling into an ordinary action node.

## Repository conventions to honor

The full list is in [`README.md`](README.md#conventions-this-repository-holds). The ones easiest
to get wrong while writing a node:

- **Namespace the type id.** `@Node.Type("<prefix>.<ClassName>")`, with the prefix from the table
  in `README.md` — `iot.` for squirrel and `camera.` for reolink, which are historical and don't
  match their library names. Every node here has one; a new one without it is a bug.
- **A new bundled dependency needs two decisions in `build.gradle`**, both commented: exclude its
  transitive `slf4j-api`, and either relocate it or say why not.
- **Add tests.** Every library has a `src/test/java` tree; a new node without one is incomplete.
- **Verify with `./gradlew build`** (compiles and tests every library) before committing.

## Tag every PR title for release

`.github/workflows/auto-tag.yml` tags and releases every merge to `main` automatically, bumping
**patch** by default. When opening a pull request, put `#minor` or `#major` in the PR title
yourself if the change warrants it — don't leave it to default to patch:

- `#major` — a breaking change: an existing node's ports, id, or saved-graph-visible behavior
  change incompatibly, or `houseGraphApi` moves to a new API major.
- `#minor` — a backwards-compatible addition: a new node, a new library, a new port on an
  existing node that doesn't change what old graphs do.
- *(no tag)* — a fix, refactor, docs change, or anything else that doesn't add or break public
  surface. This is the default, so no action needed.

Get this right at PR-creation time — the tag is read from the merge commit message, and there's
no fixing it after the merge without deleting and recreating the release tag by hand.

## Don't hand-edit `docs/shared/`

If that folder exists here, it is mirrored verbatim from HouseGraph by its `sync-docs.yml`
workflow and your edits will be overwritten on the next sync. Change the file in HouseGraph.
