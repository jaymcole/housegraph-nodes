# CLAUDE.md — housegraph-nodes

Read [`README.md`](README.md) first for how this repository is put together (one library per
subproject, the convention plugin, releasing). This file is contributor/agent guidance — things
to get right when adding or changing a node — not build mechanics.

## Node design: control vs. action

A node should almost always be **either** control-oriented (a trigger, timer, branch, loop —
decides *when* something downstream runs, doesn't do that thing itself) **or** action-oriented
(calls an API, reads a sensor, writes a file — does the work, and its flow ports report only the
outcome of *that one run*, not a schedule it manages itself), not both in one class. If a request
describes a node that would own its own scheduling/looping *and* perform an external action,
that's a smell — ask whether it should be two composable nodes instead, with the control node's
flow-out wired into the action node's flow-in. The one common exception is a *resource* node that
owns a real connection lifecycle (a Discord bot, a web server), where Start/Stop genuinely belongs
on the same node — see `AutoStartable` and `NodeContentProvider` in `housegraph-api`.

The full rationale and the worked example (`housegraph-github`'s `GitSyncNode`, before and after
splitting its timer out) live in HouseGraph's canonical write-up — read it before designing a new
node's ports:
[`docs/architecture/nodes.md`](https://github.com/jaymcole/HouseGraph/blob/main/docs/architecture/nodes.md#designing-a-nodes-ports-control-vs-action).

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
