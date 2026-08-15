# CLAUDE.md — housegraph-nodes

Read [`README.md`](README.md) first for how this repository is put together (one library per
subproject, the convention plugin, releasing). This file is contributor/agent guidance — things
to get right when adding or changing a node — not build mechanics.

## Node design: control, action, data, resource

A node should almost always fit **one** of four shapes — Control, Action, Data, Resource. Fusing
two into one class is the most common design mistake in this repo. The shapes themselves, and
why each is defined the way it is, are documented once in HouseGraph itself:
[`docs/architecture/nodes.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/nodes.md#node-roles-control-action-data-resource)
— read that first. Quick reference, with this repo's own examples:

- **Control** — a trigger, a timer, a branch, a loop, a join. HouseGraph's built-in library
  already ships the common ones (a plain trigger button, a repeating timer trigger, an `If`, a
  `ForEach`), so a library here rarely needs to reinvent one.
- **Action** — flow-in and flow-out, does the work, reports the outcome. This is the shape for a
  node that calls into a resource, too: `DiscordSendMessageNode` looks up the bot via
  `ResourceRegistry.find`, but is itself Action-shaped. `SquirrelAlarmNode`, `CameraSnapshotNode`,
  `CameraMotionStatusNode`, and `DiscoverCamerasNode` are the same shape.
- **Data** — no flow ports at all, pulled on demand. `CreateFolderNode` (`housegraph-filesystem`)
  is the canonical example — its Javadoc spells out the pattern.
- **Resource** — fronts a long-lived object registered in `ResourceRegistry` — see
  `AutoStartable` and `NodeContentProvider` in `housegraph-api`. Its flow shape follows the
  registered object's lifecycle: `DiscordBotNode` has no flow ports at all (pure Start/Stop,
  driven by its own UI); `WebServerNode`/`NodeServerNode` take a flow-in (`Rebuild`/`Restart`)
  with no flow-out, ending the branch there; `DiscordCommandNode`/`DiscordSlashCommandNode` have
  a flow-out and no flow-in, since they start a branch when Discord fires the command.

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
