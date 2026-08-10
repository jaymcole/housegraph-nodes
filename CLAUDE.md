# CLAUDE.md — housegraph-nodes

Read [`README.md`](README.md) first for how this repository is put together (one library per
subproject, the convention plugin, releasing). This file is contributor/agent guidance — things
to get right when adding or changing a node — not build mechanics.

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
Discord bot, a web server) — see `AutoStartable` and `NodeContentProvider` in `housegraph-api`.
There, "control" (Start/Stop) and "state" genuinely belong to the same node because the
connection itself is what's being managed. Treat that as a deliberate, named exception, not
precedent for fusing scheduling into an ordinary action node.
