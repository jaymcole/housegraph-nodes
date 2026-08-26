# One token, one session

Why several **Discord Bot** nodes on the same token don't each get their own connection, what this
library now does about it, and the one case that is still open — the same token in two graph
*files*.

---

## 1. The symptom

Put several Discord Bot nodes on the same token, start them at the same time — most visibly under
`housegraph daemon`, which resumes every node that was connected when the graph was saved at once —
and only one of them ends up working. The rest connect and then go quiet: no messages, no slash
commands, no button clicks. Nothing in the graph is wrong, and each of them works perfectly on its
own.

This is one symptom with two different scopes behind it, and they need different answers:

- **Several bot nodes in one graph.** One graph file is one process, however many disjoint clusters
  are drawn in it, so this is several connections opened from inside one JVM. Sections 3 and 4
  cover it, and it is fixed.
- **Two graph files on one token.** The daemon runs a process per file, so these can't see each
  other. Section 5, still open.

## 2. Why: a token is one bot, and a bot is one session

A Discord bot token addresses one bot *identity*, and that identity gets one unsharded gateway
session. A second login on the same token is not a second bot — it is the same bot logging in
again, and only one of those logins ends up holding the connection Discord delivers events to.

Sharding is not a way around this. Shards split *guilds* across sessions; they don't hand the same
event to two sessions. Two connections on two shards would each see half the servers, which is not
"both of them get their messages" — it's a different kind of broken.

So there is no arrangement in which N independent connections on one token all work. The only
thing that works is **one connection, shared**.

## 3. What this library does now

Deduplication happens at the level of the *session*, not the node's handle:

- `DiscordGateway` keeps one session per token for the process. The first `DiscordBot` to connect
  opens it; later ones on the same token join it. It closes when the last one leaves. Joining is
  serialized on the token, because the case that matters is the concurrent one: a load resumes
  every connected-at-save bot node at once, each on its own thread, and without that serialization
  two of them could both decide they were the first.
- The session owns everything that must happen once per connection: the JDA instance, the single
  event bridge (an interaction may only be acknowledged once, so deferring happens before the fan
  out), and the slash-command sync — which registers the **union** of every joined bot's commands,
  because Discord's command list belongs to the application and a bare overwrite meant whichever
  node synced last silently wiped the others'.
- Each `DiscordBot` keeps its own listeners, its own declared commands and its own button
  preferences, and every joined bot receives every event. Two Discord Bot nodes on one token both
  work; each drives its own wiring.

A node's `DiscordBot` handle is never swapped for another node's. That matters more than it looks:
Command, Slash Command and Send Buttons nodes capture the handle *when the wire appears* — during a
graph load, before anything connects — so a node that swapped its handle at Connect time would
leave every node wired to it holding one that never connects. Sharing the session underneath keeps
those captures valid.

## 4. If you wanted several bot nodes for tidiness, use a reference

One Discord Bot node wired into every workflow in a graph gets messy fast, and the obvious fix —
drop another Discord Bot node next to the far cluster and give it the same token — is the thing
section 2 says can't work.

**Discord Bot Ref** is that fix without the connection. It names a Discord Bot node and hands out
that node's handle on its own `Bot` output, so a distant cluster wires to something beside it
instead of to a wire dragged across the canvas. It owns nothing, starts nothing and holds nothing
open; everything wired to it behaves exactly as if wired to the bot node directly, because it is
the same handle.

```
[Discord Bot "home"] ──── (this corner's nodes)

[Discord Bot Ref "home"] ─ (that corner's nodes)      <- no second connection
```

The bot node publishes itself under its `Bot Name` when it joins the graph; the reference looks that
name up on every read. So load order doesn't matter, and renaming either end takes effect at once.
A name with no bot behind it resolves to nothing and says so on the node — the one way to get this
wrong.

Within one graph, this removes the reason to have a second Discord Bot node at all.

## 5. What this does not cover: one token in two graph files

All of the above is **within one process** — which, to be clear about what that includes, is one
graph *file*. Several Discord Bot nodes in one file, in disjoint clusters or not, are one process
and are covered.

`housegraph daemon` runs [one JVM per graph file](https://github.com/jaymcole/HouseGraph/blob/main/app/src/main/java/io/github/jaymcole/housegraph/remote/GraphProcess.java) —
deliberately, so one wedged graph takes only itself down and a node-library update needs only that
graph's restart. Two *files* on one token are therefore two processes that cannot see each other,
and nothing in this library can dedupe across them. This half of section 1's symptom is still
open.

If the several files exist for tidiness rather than as separate deployments, the first question is
whether they want to be one file — section 4's reference node makes a single graph with many
Discord workflows tolerable to look at, and one file is one process. Where they genuinely are
separate deployments, there are only three shapes of answer, and none of them is free:

| Option | What it gives you | What it costs |
| --- | --- | --- |
| **A bot token per graph** | Every graph gets a real, independent connection. No new code at all. | A second bot user in the server, with its own name, avatar and invite. Anything addressed to the first bot is not addressed to the second. |
| **One owner, the rest send-only** | Every Discord Bot node starts. One process holds the gateway, elected through a lock file; the others don't fight it, and can still *send* (posting a message is a REST call, which any number of processes may make on one token). | Only the owner's graph receives messages, slash commands and clicks. Which graph owns it depends on which started first, unless it's pinned. |
| **One owner, fanning out to the rest** | Every graph receives every event, as if each had its own connection. | A local IPC channel between graph processes, and an owner election that survives the owner's graph being restarted by the daemon — a real subsystem, in a library that currently has none. |

The first is the honest recommendation for most setups: if two graphs want to be two bots, make
them two bots. The second is worth building if one graph should own the conversation and the others
only need to post into it. The third is the only one that delivers what "all of them work" sounds
like it should mean, and it should not be built until something actually needs it.
