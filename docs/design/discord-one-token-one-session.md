# One token, one session

Why several **Discord Bot** nodes on the same token don't each get their own connection, what this
library now does about it, and the part that is still open — the daemon.

---

## 1. The symptom

Start two graphs that each carry a Discord Bot node on the same token, at the same time, under
`housegraph daemon`, and only one bot ends up working. The others connect and then go quiet: no
messages, no slash commands, no button clicks. Nothing in the graph is wrong, and each of them
works perfectly on its own.

## 2. Why: a token is one bot, and a bot is one session

A Discord bot token addresses one bot *identity*, and that identity gets one unsharded gateway
session. A second login on the same token is not a second bot — it is the same bot logging in
again, and only one of those logins ends up holding the connection Discord delivers events to.

Sharding is not a way around this. Shards split *guilds* across sessions; they don't hand the same
event to two sessions. Two processes on two shards would each see half the servers, which is not
"both graphs get their messages" — it's a different kind of broken.

So there is no arrangement in which N independent connections on one token all work. The only
thing that works is **one connection, shared**.

## 3. What this library does now

Deduplication happens at the level of the *session*, not the node's handle:

- `DiscordGateway` keeps one session per token for the process. The first `DiscordBot` to connect
  opens it; later ones on the same token join it. It closes when the last one leaves.
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

## 4. What this does not cover: the daemon

All of the above is **within one process**.

`housegraph daemon` runs [one JVM per graph](https://github.com/jaymcole/HouseGraph/blob/main/app/src/main/java/io/github/jaymcole/housegraph/remote/GraphProcess.java) —
deliberately, so one wedged graph takes only itself down and a node-library update needs only that
graph's restart. Two *graphs* on one token are therefore two processes that cannot see each other,
and nothing in this library can dedupe across them. Section 1's symptom is exactly this case, and
it is still open.

There are only three shapes of answer, and none of them is free:

| Option | What it gives you | What it costs |
| --- | --- | --- |
| **A bot token per graph** | Every graph gets a real, independent connection. No new code at all. | A second bot user in the server, with its own name, avatar and invite. Anything addressed to the first bot is not addressed to the second. |
| **One owner, the rest send-only** | Every Discord Bot node starts. One process holds the gateway, elected through a lock file; the others don't fight it, and can still *send* (posting a message is a REST call, which any number of processes may make on one token). | Only the owner's graph receives messages, slash commands and clicks. Which graph owns it depends on which started first, unless it's pinned. |
| **One owner, fanning out to the rest** | Every graph receives every event, as if each had its own connection. | A local IPC channel between graph processes, and an owner election that survives the owner's graph being restarted by the daemon — a real subsystem, in a library that currently has none. |

The first is the honest recommendation for most setups: if two graphs want to be two bots, make
them two bots. The second is worth building if one graph should own the conversation and the others
only need to post into it. The third is the only one that delivers what "all of them work" sounds
like it should mean, and it should not be built until something actually needs it.
