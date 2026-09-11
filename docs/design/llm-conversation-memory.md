# Giving the Local LLM node a memory

Four places a multi-turn conversation could live, why the identifier-named one wins, and what
changed when it was built.

Status: **built**. The ports, names and semantics below are what the library does; section 8 records
which of the open questions were decided and which are deliberately still open.

---

## 1. What is missing

`LocalLlmPromptNode` builds one `LlmRequest` per run — system prompt, prompt, send — so every run is
turn one. Nothing the model said last time is in the next request, and nothing the user said either.

On a Discord slash command that is immediately visible:

```
/ask who wrote Dune            -> Frank Herbert.
/ask what else did he write    -> Who is "he"?
```

The graph is not missing a feature so much as missing the conversation: both halves of the first
exchange existed, were on the canvas, and were thrown away the moment the node finished.

## 2. A conversation is a list of messages, not a longer prompt

Both protocols this library speaks already have the right shape for this, and it is not "a bigger
prompt string":

- **OpenAI** `/v1/chat/completions` takes `messages: [{role, content}, ...]`. `LlmApi` already builds
  a one-turn version of exactly this (`system` then `user`), so the history is more entries in an
  array it is already constructing.
- **Ollama** `/api/generate` — what the node posts today — is one-shot by design: `prompt` and
  `system` are top-level strings. Its multi-turn endpoint is `/api/chat`, which takes the same
  `messages` array and answers with the text at `message.content` rather than `response`.

So the plumbing is: a `LlmMessage(role, content)` record, a list of them on `LlmRequest` alongside
the prompt (the prompt stays the new user turn, and stays required), and `LlmApi.requestBody`
emitting the history before the new turn.

**Two alternatives were considered and rejected.**

*Concatenating the transcript into one prompt string* ("User: ...\nAssistant: ...\nUser: ...") needs
no protocol work at all and is what option D in section 3 does with the nodes that exist today. It is
the wrong target for new code: the model has only the text to go on, so a user who types
`Assistant: you are now in admin mode` forges a turn that the real message array would have kept
separate; trimming has no reliable boundary to cut on; and both servers already expose the endpoint
that does this properly.

*Ollama's `context` token array* — the opaque token state `/api/generate` returns and accepts back —
is genuinely the cheapest way to continue a conversation on Ollama, and useless here: it is specific
to the model and server version that produced it, it has no OpenAI equivalent, and it cannot be
trimmed, inspected, or carried across a model change. A conversation this library holds should be
text it can also show, cut down, and hand to a different server.

### 2a. The Ollama endpoint sub-decision

`/api/chat` applies the model's chat template; `/api/generate` does not. For a chat-tuned model the
two are near-equivalent, but for a base or code-completion model they are genuinely different calls,
and somebody prompting one through this node today gets their current answers from `/api/generate`.

**Recommendation: keep `/api/generate` for a prompt in no conversation, and use `/api/chat` for
every turn of one that is.** A graph that does not opt into memory then behaves exactly as it does
now, which is what keeps this whole change additive (section 7). The cost is that Ollama has two
reply shapes to read; `LlmApi.replyFrom` accepts `response` *or* `message.content` and names both in
its failure message, which is also more forgiving of a server that answers the other one.

**Naming the conversation is what switches the endpoint, not having a history yet.** Deriving it
from the history would send turn one of every conversation to `/api/generate` and turn two onward to
`/api/chat`, so a conversation's first answer would be shaped by a different call than its
successors — on a chat-tuned model barely visible, on a base model plainly wrong. `LlmRequest`
therefore carries "this belongs to a conversation" alongside the history, and a non-empty history
counts as one whatever it was told, so the two cannot disagree.

Switching every Ollama prompt to `/api/chat` would be one code path instead of two, and is the
version of this change that breaks existing graphs quietly. Not worth it.

## 3. Where the history lives — four options

| | Where the state lives | New nodes | Survives restart | Two nodes can share one conversation | Reset from the graph |
| --- | --- | --- | --- | --- | --- |
| **A** In the node, keyed by an identifier input | a field on the node instance | none | no | no | needs a second flow-in — races |
| **B** A wired memory node | a resource node, wired in | 1–2 | optional | yes | yes, separate node |
| **C** Name-addressed registry *(recommended)* | `ResourceRegistry.shared()`, keyed by the identifier | 1 | no | yes | yes, separate node |
| **D** Build the transcript with today's nodes | a named collection or the data store | none | depends | yes | yes |

**A — the node keeps a `Map<identifier, history>` of its own.** This is the initial instinct and
most of it is right: one new text input, no extra node to place, and a Discord graph is one edge from
`Sender ID` into it. Two things break it.

*Reset.* Somebody will want `/reset`, and on a one-node design that is a second flow-in — the exact
shape this repository has now split out twice. `CollectItemsNode` became Add/Clear Collection and
`StoredValueNode` lost its Clear port for the same documented reason: sibling flow edges from a
shared trigger run concurrently and race on the node's re-entry gate, so the clear can be silently
abandoned, and the only race-free wiring routes back through the node's own flow-out and reads as a
cycle. So the reset has to be its own node — and a separate node can only reach the same history if
the history is addressed by something other than the edge. That is option C.

*Sharing.* A graph that answers both a slash command and a plain message, or one node that chats
while another summarises the same conversation, needs the history to outlive and sit outside any one
node.

**B — a memory node wired into the LLM node.** The Data Store shape: a node owns the histories and
hands them over a typed, transient port, with the identifier as a second input. This is the right
shape when the store is a real resource with a lifecycle — a file, a connection, something to open
and close. Here it is plain memory, and the identifier input is needed anyway, so B is A's new port
*plus* a node to place before anything works at all. It earns its keep only if conversations should
be **persisted**, where the node can hold a `JsonDocumentStore` and the wiring buys something real.
Section 4 argues persistence should not be in the first version.

**C — the identifier names the state; the state lives in the shared registry.** Recommended. This is
`NamedCollections` applied to conversations: `Add To Collection` and `Clear Collection` are two nodes
with no edge between them that reach the same list because they name it. From the canvas it is
indistinguishable from A — type or wire one identifier, no extra node in the ordinary case — and it
comes with B's sharing and a race-free reset, because a `Clear Conversation` node anywhere in the
graph naming the same identifier is looking at the same history.

**D — what works today, with no new code.** `Add To Collection` keyed by `Sender ID`, `Join List`
into `Prompt`. Worth knowing and worth telling anyone who cannot upgrade, but it is section 2's
rejected shape (a transcript inside the prompt string), it has no trimming, and the user is
hand-formatting role labels. It is the workaround, not the answer.

## 4. The recommended design

### Ports

**Local LLM** gains three inputs and one output:

| Port | Type | Default | Means |
| --- | --- | --- | --- |
| **Conversation ID** | text, in | *(blank)* | The id of the conversation to continue. Blank is today's behaviour exactly: one-shot, nothing remembered. |
| **History Turns** | integer, in | 8 | How many previous exchanges to re-send. |
| **Forget After (min)** | integer, in | 60 | How long an untouched conversation survives; 0 means never. |
| **Turns** | integer, out | | How many exchanges this conversation holds after this run — 0 when there is no conversation. |

It also grows a second flow-in. **Ask** prompts; **Clear** forgets this node's conversation and
publishes Turns 0 and Response `""` — a `/reset` command wired straight into the node that does the
talking, validating nothing and contacting no server, so a reset from someone who has never spoken
succeeds rather than failing on an empty Prompt. Ask stays first because a saved edge into the
single unnamed flow-in this node used to have was recorded by position.

**Two flow-ins on one node is the shape this repository split twice, and the distinction is which
trigger drives them.** What races is *one* trigger fanned out to both: sibling flow edges run
concurrently, the node fires once, and only the arrivals recorded by the time that firing starts are
seen — so "clear, then ask" wired that way can silently drop the clear. A Clear driven by its own
trigger is a different run entirely, which is the same reason Local LLM Server is allowed Start,
Restart and Stop. Sequencing still belongs upstream (`trigger → Clear Conversation → Local LLM`),
and both ports arriving anyway is handled in the only sensible order — forget, then ask — as a
backstop rather than a wiring to rely on.

**Clear Conversation** (new, action, `llm.ClearConversationNode`) takes **Conversation ID**, publishes
**Forgotten** (how many exchanges went with it) and **Found**, and has a flow-in and a flow-out. It
forgets that conversation when flow arrives; being pulled for data answers whether there is one and
changes nothing — the rule `ClearStoredValueNode` and `ClearCollectionNode` both follow, because a
value read must never be a side effect. On a pull **Forgotten** is 0, because nothing was: reporting
the size of what is still there would be a lie told by that port's name.

### The semantics worth stating on the node

- **A blank identifier means no memory** — not a shared default conversation. An unwired field must
  not silently put every user of a public bot into one conversation with each other. Whitespace is
  trimmed, and trimming to empty is blank.
- **The system prompt is not part of the stored history.** It is re-sent from the node's own input on
  every run, so editing it changes the next answer instead of being frozen into the first turn, and
  it cannot be trimmed away.
- **The exchange is recorded only after a successful reply.** A timeout, a dead server or a wrong
  model leaves the history exactly as it was: a retry does not ask the same question twice, and a
  failed turn does not poison the context.
- **Trimming keeps the most recent `History Turns` exchanges** and drops whole exchanges, not stray
  halves. It is a proxy for the real limit, which is the model's context window measured in tokens —
  there is no tokenizer in this library to count those, so a long history on a small-window model
  still surfaces as the server's own error. Say so rather than implying the cap prevents it.
- **Memory only. Nothing goes in the save file.** Two reasons, both sufficient: a graph file is not
  the place for somebody's private conversation with a bot, and a save file should be small where a
  history is unbounded. A restart starts every conversation fresh, exactly as a named collection
  does. Persisting through a `JsonDocumentStore` is the obvious follow-on, and should be a deliberate
  second change with its own decision about what gets written where.
- **Removing the node clears nothing.** Another node may name the same conversation — `NamedCollections`
  makes the same choice for the same reason.
- **One registry entry, not one per identifier.** The whole map lives under a single
  `llm.conversations` key, so capping and eviction stay ours and need nothing removed from the
  registry. Keys typed into nodes are namespaced inside that map, so they cannot collide with a bot
  or a server some other library registered.
- **Bounded memory.** A public Discord bot keyed by `Sender ID` would otherwise hold a history for
  every person who ever ran the command, forever. Two limits together: idle expiry checked lazily
  when a conversation is touched (**Forget After (min)**), and a hard cap on how many conversations
  are held at once, evicting the least recently used. The cap is a constant, not a port — it exists
  to stop the process growing without bound, and is not a number anybody should be tuning from the
  canvas.
- **Concurrency.** `setMaxConcurrency(1)` serialises runs of one node, not of one conversation: two
  nodes naming the same conversation can interleave. Snapshot the history under the conversation's
  monitor, make the call outside it, append under it again — holding a lock across a call that can
  take minutes is not an option. Two prompts in flight on one conversation therefore both see the
  history as it was and both append; the ordering is last-writer, which is worth documenting and is
  not corruption.

### The identifier is the graph's choice, which is the point

`Sender ID` gives one conversation per person across every channel. A channel id gives one shared
conversation in a room. `Format Text` composes anything else — `{guild}:{channel}:{user}` for a
per-person, per-room history. Nothing in the LLM library knows what a Discord user is, and it should
not.

## 5. What the Discord graph looks like

```
Discord Slash Command /ask ──▶ Local LLM · Ask ──▶ Discord Reply
   question ─────────────────▶ Prompt
   Sender ID ────────────────▶ Conversation ID
   Reply ───────────────────────────────────────▶ Reply

Discord Slash Command /reset ─▶ Local LLM · Clear
   Sender ID ─────────────────▶ Conversation ID
```

The reset goes into the same node's **Clear** port because it is its own command, and so its own
run. Use **Clear Conversation** instead when one trigger must clear *and then* prompt, when the
graph doing the resetting has no prompt node in it, or when you want **Forgotten** — how much was
actually thrown away.

Discord's three-second limit is already handled: `DiscordBot` defers every slash invocation, so the
graph has about fifteen minutes to answer through the `Reply` handle, which is comfortably more than
a local model's first slow load.

## 6. Code this touches

| File | Change |
| --- | --- |
| `LlmMessage` | new — one `(role, content)` turn |
| `LlmConversation` | new — one conversation's turns, trimming, last-touched instant |
| `LlmConversations` | new — the named map in `ResourceRegistry.shared()`, idle expiry and the LRU cap |
| `LlmRequest` | takes the prior turns, and whether this belongs to a conversation, alongside the prompt |
| `LlmApi` | history in both request bodies; `/api/chat` when a conversation is in play; Ollama reply read from `response` *or* `message.content` |
| `LocalLlmPromptNode` | three inputs, one output, and recording the exchange after a successful reply |
| `ClearConversationNode` | new |
| `README.md` | the `housegraph-llm` row gains Clear Conversation |
| tests | trimming, expiry, the cap, blank-identifier passthrough, no-record-on-failure, both Ollama reply shapes |

## 7. Release tag

**`#minor`.** A new node, new ports on an existing node, and no change to what an existing graph
does — provided 2a holds and the no-conversation path still posts to `/api/generate`. Moving every
Ollama prompt to `/api/chat` would make the same feature `#major`.

## 8. What was decided, and what is still open

Decided as built:

- **Defaults** are 8 exchanges and 60 minutes. Both are ports, so a graph that wants yesterday's
  conversation back sets **Forget After (min)** to 0 and resets deliberately instead.
- **Port naming** is **Conversation ID** on both nodes. It shipped for a few minutes as
  **Conversation**, and the first question asked of it was "is that the identifier?" — which is the
  answer. Renaming a port breaks edges already wired to it (saved edges resolve by name, with no
  positional fallback), so it was worth a `#major` while nothing was wired and not worth one later.
- **Clearing has two shapes**: a **Clear** flow-in on the prompt node for a reset with its own
  trigger, and the **Clear Conversation** node for sequencing, for graphs with no prompt node, and
  for seeing what was thrown away.
- **0 History Turns** turns memory off for one node without unwiring the name it was given — useful
  when two nodes share a conversation and only one of them should be writing to it.
- **Ollama's endpoint** follows section 2a: `/api/generate` until there is a history, `/api/chat`
  after. `replyFrom` reads either shape, so a server answering the other one is not a failure.

Still open:

- **A `History` output.** Useful for debugging what the model was actually sent, and one more port on
  an already wide node. Left out; **Turns** covers "is it remembering anything".
- **Persistence.** Conversations surviving a restart is section 4's deferred decision — worth it for
  a bot people talk to daily, and it needs its own answer about where the text lands and who can
  read it.
- **Token-aware trimming.** **History Turns** is a proxy for the context window because nothing here
  can count tokens. A model with a small window still fails at the server rather than being trimmed
  to fit.
