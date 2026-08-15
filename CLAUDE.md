# CLAUDE.md — housegraph-nodes

Read [`README.md`](README.md) first for how this repository is put together (one library per
subproject, the convention plugin, releasing). This file is contributor/agent guidance — things
to get right when adding or changing a node — not build mechanics.

## Node design: control, action, data, resource

A node should almost always fit **one** of four shapes. Fusing two into one class is the most
common design mistake in this repo — picking the right shape up front keeps a node reusable and
directly testable.

- **Control** nodes shape *when* and *how often* flow moves: a trigger, a timer, a branch, a
  loop, a join. Their job is deciding whether/when something downstream runs — not doing that
  something themselves. (HouseGraph's built-in library ships several of these — a plain trigger
  button, a repeating timer trigger, an `If`, a `ForEach` — so a library here rarely needs to
  reinvent one.)
- **Action** nodes *do* something: call an API, read a sensor, write a file, transform data.
  Always a flow-in and a flow-out; the flow-out exists only to report that the node ran and, at
  most, which of a small number of known outcomes happened for *that one run* — not to decide
  independently when to run again. This is the shape for a node that calls into a resource, too:
  `DiscordSendMessageNode` looks up the bot via `ResourceRegistry.find`, but is itself
  Action-shaped — one flow-in, one flow-out. `SquirrelAlarmNode`, `CameraSnapshotNode`,
  `CameraMotionStatusNode`, and `DiscoverCamerasNode` are the same shape.
- **Data** nodes have no flow ports at all. They exist purely to be pulled: one or more data
  outputs, computed or fetched on demand, nothing to trigger and nothing to report.
  `CreateFolderNode` (`housegraph-filesystem`) is the canonical example — its Javadoc spells out
  the pattern.
- **Resource** nodes front a long-lived object registered in `ResourceRegistry` — see
  `AutoStartable` and `NodeContentProvider` in `housegraph-api`. Its flow shape follows the
  registered object's lifecycle rather than the Control/Action pattern, and varies more than the
  other three on purpose: `DiscordBotNode` has no flow ports at all (pure Start/Stop, driven by
  its own UI); `WebServerNode`/`NodeServerNode` take a flow-in (`Rebuild`/`Restart`) with no
  flow-out, ending the branch there; `DiscordCommandNode`/`DiscordSlashCommandNode` have a
  flow-out and no flow-in, since they start a branch when Discord fires the command. A one-sided
  flow shape is normal for this category, not a smell — it's a smell only for Action nodes.

**Why the Control/Action split matters, concretely:** `housegraph-github`'s `GitSyncNode`
originally owned both — its own `Start`/`Stop` timer *and* the git sync itself, in one class. That
made it impossible to reuse with a different schedule, harder to test (the timer and the action
were welded together), and duplicated what a repeating-trigger node already does. Splitting the
timer out — the node now has a flow-in and expects something else to drive it — left it a plain
action node: `Checked` always fires (it ran), `Pulled` fires only when that run's outcome was
"found and pulled a new commit." That's the shape to reach for by default: an action node's
branches describe the outcomes of one invocation, not points on a schedule it manages itself.

**When a request describes a node that would own its own scheduling/looping/branching-on-a-timer
*and* perform an external action, don't build the fused version by default — ask whether the
control part (the trigger/timer/loop) and the action part should be two composable nodes
instead**, with the control node's flow-out wired into the action node's flow-in. Resource is the
one deliberate, named exception to "control and action stay separate": there, Start/Stop and
state genuinely belong to the same node because the connection itself is what's being managed —
not precedent for fusing scheduling into an ordinary action node.

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
