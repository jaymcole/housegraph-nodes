# Starting a local LLM server from a graph

Why `housegraph-llm` now runs the model server as well as prompting it, why a server that is
already up gets **adopted** rather than restarted, and what a graph can and cannot control as a
result.

---

## 1. The gap

The **Local LLM** node prompts a model server on this machine. It does not start one. So every
graph that used it carried an unwritten prerequisite: somebody had typed `ollama serve` into a
terminal, and somebody had run `ollama pull llama3.2`, and both of those had to still be true.

On a desktop that is a small annoyance. On the machine these graphs actually run on — a box in a
cupboard, restarted by a power cut, supervised by `housegraph daemon` — it is the difference
between an automation that works and one that stops working the next time the machine reboots, with
"nothing is listening at http://localhost:11434" as the only clue.

Every other outside-the-graph resource in this repository is brought up by a node: the web server,
the Node.js server, the database, the Discord gateway. The model server was the exception, and
there was no reason for it to be.

## 2. What was added

Three nodes, in the library that already owned this ground:

| Node | Kind | Does |
| --- | --- | --- |
| **Local LLM Server** | resource | Runs the server as a child process and waits until its API answers. Start / Stop / Restart, `Ready` / `Stopped`. |
| **Pull Model** | action | Makes sure an Ollama server has a model, downloading it if not. `Ready` always, `Pulled` only when it fetched. |
| **LLM Server Status** | action | Answers whether a server is up, and branches: `Running` / `Not Running`. |

A whole setup is then one graph: a trigger into **Local LLM Server**, its `Ready` into **Pull
Model**, its `Ready` into **Local LLM**, and the server node's `Server` output wired into the
prompt node's `Server` input so the address is stated once.

**The server node is the control-versus-action rule's named exception**, not a break with it. It
owns a real process lifecycle, which is the case
[`node-library-rules.md`](../shared/node-library-rules.md#node-design-control-or-action-not-both)
allows Start/Stop and state to live on one node — the same reason the Web Server and Database nodes
own theirs. Nothing in the library schedules anything: when to prompt is still a trigger's job.

## 3. Readiness is an API answer, not a bound port

`housegraph-web`'s `NodeProcessServer` waits for its child to accept a TCP connection before
calling it started, because `ProcessBuilder.start()` succeeding only means the shell launched. That
is the right postcondition for a web server. It is the wrong one for a model server.

A model server binds its port, *then* reads its model index — and a TCP probe goes green in
between. A graph that prompted in that window got a connection reset from a server that was
technically running. So `LlmServerProcess` polls the server's own model-list endpoint instead
(`/api/tags`, or `/v1/models` for an OpenAI-compatible one) and calls it up only once that answers.

Two things fall out of that choice, both of them worth more than the round trip they cost:

- **It tells the difference between the server being slow and something else being on the address.**
  A connect probe cannot: anything bound to that port answers it. The model-list probe gets a reply
  in the wrong shape, and says so.
- **It is the same call the status node makes**, so "is it up" has one implementation and one set of
  failure messages, whether it is being asked once by a graph or twenty times a second by a start
  that is waiting.

## 4. A server that is already running is adopted

This is the decision with the most consequences, and it is not an optimisation.

**Ollama is normally installed as a background service.** The macOS and Windows apps start one at
login; most Linux packages install a systemd unit. On a great many machines something is *already*
serving `localhost:11434` before HouseGraph starts. Spawning a second `ollama serve` there does not
produce a second server — it produces "address already in use", a child that dies seconds after
`start()` returned, and a node that is permanently red on a machine where local LLMs work fine.

So the node looks before it spawns. If the address already answers, that server is **adopted**: no
process is launched, the node reports running, `Ready` fires, and everything downstream works
exactly as it would have.

**What is adopted is not owned.** Stop leaves an adopted server running and says so in the log. A
node has no business killing a system service it did not start, and a Restart that took the
machine's Ollama down with the graph would be a worse failure than one that does nothing. The
consequence is stated on the node rather than left to be discovered: **Restart on an adopted server
does nothing**, and the status line says which of the two the node has.

The alternative — kill whatever is on the address and start our own — was rejected on the same
grounds this repository's `SpawnRecord` refuses to reap by port: acting on an address kills whatever
happens to be at it, and being wrong about that is expensive in a way that one clear message is not.

## 5. Two ways the address can be poisoned, and the answers

**A start that thinks it succeeded.** Restart is stop-then-start, and start's first move is the
adoption probe. A `stop()` that returned as soon as the process was signalled would leave the old
server still draining — and the probe would find it, adopt it, and report a successful restart of a
process that is on its way out. So `stop()` blocks until the address has actually fallen silent,
the same reasoning (and roughly the same code) as `NodeProcessServer` waiting for its port.

**A JVM that never ran its teardown.** A HouseGraph that is killed, crashes, or overruns its
shutdown budget leaves its `ollama serve` running with nobody's hand on it. For a web server that
means the next start fails on `EADDRINUSE`, which is at least loud. For a model server it is worse:
the next run's readiness probe finds the *old* server answering, adopts it, and reports a healthy
start while a stale process quietly serves every prompt.

An in-process guard cannot fix that, because the process that would run it is the one that died. So
`LlmServerRecord` writes the pid and its start instant to disk, and the next start reaps it before
it probes. It reaps only when **both** still match, so a pid the OS has recycled onto something
unrelated is left alone — an orphan costs one clear error message, and killing the wrong process
costs much more. It is `housegraph-web`'s `SpawnRecord` applied to the other long-lived child
process this repository spawns, deliberately copied rather than shared: each library ships as its
own jar with its own class loader and they cannot depend on each other.

## 6. Pulling a model is Ollama's alone

**Pull Model** takes no API setting. Ollama has a model registry and an API to fetch from it;
llama.cpp, LM Studio and vLLM are pointed at a file or a Hugging Face id that somebody put on the
disk themselves, and have no equivalent endpoint to call. Pretending otherwise would mean a node
that appears to work and silently does nothing on three of the four servers this library supports.

Two details are load-bearing:

- **It checks by the name a person types.** Ollama tags its models, so a machine with `llama3.2`
  pulled reports `llama3.2:latest` — and `llama3.2` is what the Local LLM node ships with in its
  Model field. Comparing those literally would re-download a model that is already there, on every
  run. A Model that names a tag is matched exactly, since asking for a specific tag means it.
- **A 200 is not a success.** Ollama reports a model that does not exist in the registry in the
  *body* of an HTTP 200. Reading only the status code would report a typo as a successful download
  and leave it to surface as "model not found" on the prompt node some minutes later.

`Ready` fires whenever the model is present afterwards; `Pulled` fires only when this run fetched
it. That is the Git Sync node's `Checked`/`Pulled` split, for the same reason: the common case is a
no-op, and a graph should be able to tell the two apart.

## 7. What is still not controlled from a graph

- **Stopping a server this library did not start.** By design — see section 4. Someone who wants the
  machine's Ollama under graph control should disable the service and let the node run it.
- **Progress on a pull.** Ollama streams it, but there is no port a percentage could go out of, so
  the request asks for the single final answer. A pull that is running shows up as a node that is
  taking a long time; the `[llm]` lines in HouseGraph's log are where to watch if the server is one
  a Local LLM Server node started.
- **Which GPU, how much VRAM, how many models stay resident.** These are the server's own
  environment and flags. They belong in the **Command**, which is an ordinary shell command and
  takes them as typed.
