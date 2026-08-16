# CLAUDE.md — housegraph-nodes

Read [`README.md`](README.md) first for how this repository is put together (one library per
subproject, the convention plugin, releasing). This file is contributor/agent guidance — things
to get right when adding or changing a node — not build mechanics.

## Node design: control vs. action

The rule — a node should almost always be **either** control-oriented **or** action-oriented,
not both, with a connection-owning resource node as the named exception — is stated in
[`docs/shared/node-library-rules.md`](docs/shared/node-library-rules.md#node-design-control-or-action-not-both),
along with every build rule. Read that first.

**The worked example is in this repository.** `housegraph-github`'s `GitSyncNode` originally
owned both — its own `Start`/`Stop` timer *and* the git sync itself, in one class. That made it
impossible to reuse with a different schedule, harder to test (the timer and the action were
welded together), and duplicated what a repeating-trigger node already does. Splitting the timer
out — the node now has a flow-in and expects something else to drive it — left it a plain action
node: `Checked` always fires (it ran), `Pulled` fires only when that run's outcome was "found and
pulled a new commit."

**When a request describes a node that would own its own scheduling/looping/branching-on-a-timer
*and* perform an external action, don't build the fused version by default — ask whether the
control part and the action part should be two composable nodes instead**, with the control
node's flow-out wired into the action node's flow-in.

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
