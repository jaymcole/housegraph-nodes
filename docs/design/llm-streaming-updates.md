# Watching the Local LLM node work

Where a progress signal can come from, why it is a second flow output on the node that does the
prompting rather than a node of its own, and what is still missing downstream.

Status: **built**. The ports, names and semantics below are what the library does; section 7 records
what was decided and what is deliberately still open.

---

## 1. What is missing

`LocalLlmPromptNode` is silent for as long as it runs. A local model answering a real question takes
tens of seconds, and on a Discord slash command that is tens of seconds of Discord's own
"thinking…" with nothing behind it — no sign the model is working, no sign it is stuck, and no way
to tell the two apart until the timeout does it.

The model is not actually silent. Both servers this library speaks to will stream an answer as they
generate it; the node asks them not to. `LlmApi.requestBody` has sent `"stream": false` since the
node existed, and `LlmModels.pull` says the quiet part out loud about the same problem in the
Pull Model node:

> The pull is not streamed. […] there is no port a percentage could go out of.

That was true. It is what this design changes.

## 2. What Ollama actually gives us

`"stream": true` turns one reply into a run of newline-separated JSON objects, one per token or so:

```json
{"model":"llama3.2","response":"The","done":false}
{"model":"llama3.2","response":" sky","done":false}
{"model":"llama3.2","response":"","done":true,"eval_count":259,"total_duration":10706818083}
```

`/api/chat` is the same with the text at `message.content`. An OpenAI-compatible server streams
server-sent events instead — `data:` lines carrying `choices[0].delta.content`, ended by
`data: [DONE]` — which is different framing around the same idea, and is why `LlmApi.chunkFrom`
takes a line rather than a body.

**Reasoning is a separate field, and asking for it is not free.** A thinking model told to think
puts its chain of thought in `thinking` (Ollama) or `reasoning_content` (llama.cpp, vLLM) and its
answer in the ordinary content field. The asking is the catch: sent a top-level `think` for a model
that cannot think, Ollama answers `HTTP 400 "<model>" does not support thinking`. So the Think input
is blank by default and blank sends no field at all — not `false`, which is a different request —
and every graph pointed at an ordinary model keeps working. Reasoning is still *read* whenever a
server sends it, so a model that reasons of its own accord populates Thinking without the field.

## 3. Four shapes, and why the fused one won

**A. A second flow output on the prompt node.** ✅ Built.

**B. A separate streaming node.** Twelve duplicated inputs and a second copy of the conversation
path, for one difference in behaviour.

**C. A side-channel trigger node** — the prompt node publishes under a stream id, a separate node
subscribes and fires its own flow, the way `DiscordCommandNode` subscribes to a bot. Clean on
paper, and it would keep the action node action-shaped. It loses on ordering: the updates would run
as independent concurrent runs racing the main one, and a status edit landing after the final answer
overwrites the answer with "thinking…". For the case this was built for that is disqualifying.

**D. Nothing — leave it to the log.** What Pull Model does today. Fine for a machine being watched
by its owner, useless for a bot answering someone else.

### The rule this bends

[`node-library-rules.md`](../shared/node-library-rules.md#node-design-control-or-action-not-both)
says an action node's flow outputs report "which of a few known outcomes happened **for that one
invocation**" — and a port that fires N times during one invocation is loop-shaped, not
outcome-shaped.

It is allowed here for the reason `ForEachNode`'s Body port is: the repetition is not a schedule the
node invented, it is an external stream of events arriving, and **only the thing holding the socket
knows when one arrived**. Every way of splitting it lands on option C and its ordering race. This is
a narrow exception — a node that *polls* something on a timer and reports progress is still the smell
the rule is about — not a licence to put a loop on any action node.

## 4. How it works

`BaseNode.runFlowBranchToCompletion(port, seed)` runs the branch hanging off one flow output as an
isolated sub-run and **blocks until it quiesces**. It exists for loop bodies; a throttled progress
update is the same shape, with the model's output in place of a list.

Blocking is what buys the whole design:

- **Ordering.** Every Update branch has finished before the next starts, and all of them before the
  unnamed output fires with the answer. A status update cannot land after the answer that replaces
  it — the property the Discord case turns on.
- **Back-pressure for free.** Tokens arriving while an Update branch is out are accumulated, and the
  next update carries the newest state. A slow consumer makes updates *less frequent* rather than
  putting the node further and further behind. A Discord edit is a network round trip, so this is
  the difference between "updates every second or so" and "a queue of edits the run never drains".

`LlmProgress` holds the accumulating and the throttling, away from both the socket and the graph, so
the interesting logic is unit-testable without either.

### The trap that came with the second port

A node that activates *no* flow output fires *all* of them. `LocalLlmPromptNode` never called
`activate()` because it had one port and the default was free. With two, the default would fire one
Update alongside the answer at the end of every run — the status display flickering back to the
half-answer it had just replaced. The node now ends with `activate(out)`, and
`StreamingUpdateGraphTest.aRunThatDoesNotStreamNeverFiresUpdate` is there to keep it.

`PullModelNode` already carries a comment about the same default for the same reason.

## 5. The ports

| | Name | |
| --- | --- | --- |
| in | `Update Every (ms)` | 0 — off — by default. Streaming costs a sub-run per interval, and an unwired Update port should cost nothing. 1000 is a sensible first value. |
| in | `Think` | Blank by default, and blank sends no field. `true`, `false`, or a level. Ollama only. |
| out | `Answer So Far` | Everything answered up to this update — for a display that *replaces*, such as a Discord edit. |
| out | `New Text` | Only what arrived since the last update — for one that *appends*. |
| out | `Thinking` | The reasoning so far; `""` for a model that isn't reasoning. |
| out | `Phase` | `thinking`, `answering`, or `done` on the final firing. |
| flow out | `Update` | Fired repeatedly during a streamed run; never when Update Every (ms) is 0. |
| flow out | `` (unchanged) | Fires once, at the end, with `Response`. Still the first port, so a graph saved before this loads unchanged. |

**Response is empty on an Update**, deliberately. A run that has not finished has no answer, and
publishing the previous run's would put the last person's reply into this person's status message.

**A phase change does not wait for the interval.** Left to the clock, a model whose answer arrives
less than an interval after its last reasoning would finish without ever publishing an `answering`
update, and a graph watching Phase would never see it change. There is at most one such transition
in a run.

## 6. What this does not fix

**The Discord side is half-ready.** A slash command works today: `DiscordReply` is
`hook.editOriginal`, so firing a Discord Reply node repeatedly edits the same message in place,
which is exactly the behaviour wanted. Two gaps remain, both in `housegraph-discord`:

- **Past 2000 characters it misbehaves.** `replyThrough` splits long text and posts the overflow as
  *follow-up messages*. Called repeatedly on an answer that grows past the limit, every update after
  that posts another follow-up. An "edit only" mode, or a cap on the streamed preview, is needed
  before streaming a long answer into a reply.
- **A plain channel message cannot be edited at all.** `DiscordBot` has no `editMessage` and Send
  Message emits no message id, so the `!command` path has nothing to update. That needs a message
  handle out of Send Message and an Edit Message node.

**Pull Model still does not stream**, though `/api/pull`'s progress is the same shape and there is
now somewhere for it to go.

## 7. Decided, and still open

**Decided.**

- Fused onto the prompt node (option A), against the rule's default, for the ordering reason in §3.
- Off by default, so an existing graph does exactly what it did.
- `Phase` as a published value rather than a flow output for the thinking→answering transition: it
  is strictly more information, and it needs no special case for the models that never think. A
  `Thought` port could still be added later without breaking anything.
- Both `Answer So Far` and `New Text`, rather than guessing which one a graph wants.
- A failed run still records nothing into the conversation. What was already shown stays shown —
  nothing un-sends a Discord edit — so a failure part-way leaves half an answer on screen and a
  failed node, and a retry starts clean.

**Still open.**

- **Timeout is still the whole answer, not an idle timeout.** "No token for N seconds" is a better
  question about a streamed run than "the whole answer in N seconds", but changing the meaning of an
  existing input is a change to every graph that set it. A separate input, or a major version, is
  where that belongs.
- **The final update can duplicate the answer.** Nothing can tell that a piece is the last one until
  the server says so, so an update landing on the last piece publishes text the end of the run
  publishes again. One redundant edit, on the runs where the timing falls that way. The alternative
  — holding every update back by one piece — costs latency on every run to save an edit on some.
- **Cancellation is checked between pieces, not during one.** A read blocked on a socket is not
  interruptible, so a superseded run ends within a token or so rather than instantly. That is a
  large improvement on not stopping at all, which is what the unstreamed call does.
