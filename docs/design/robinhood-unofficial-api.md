# Robinhood, and an API that doesn't exist

Why `housegraph-robinhood` talks to an interface nobody published, what that costs whoever installs
it, and the decisions that follow from it — the login, the dry-run default, and where the library
will break first.

---

## 1. There is no Robinhood API

Robinhood has never published a trading API for retail customers. There is no documentation, no
versioning policy, no deprecation window, and no support channel for any of it. What exists is the
private HTTP interface Robinhood's own web and phone apps use, which a handful of open-source
clients — `robin_stocks` and its relatives — have tracked by observation for years.

That is what this library speaks. Everything else in this document follows from it.

**Three consequences, none of them hypothetical:**

- **It can stop working overnight.** A renamed field, a moved endpoint, a new required header, and
  nodes in this library start failing with no warning. That is not a bug in this library; it is the
  arrangement.
- **It is very likely against Robinhood's terms of service.** Whether that matters, and whether an
  account can be restricted for it, is between the account holder and their broker. Installing this
  library is taking that on, and the **Robinhood Account** node's own documentation says so where
  somebody will actually read it.
- **A bug here costs real money.** Everywhere else in HouseGraph, a trigger wired to the wrong port
  is a wasted run. Here it is an order.

The alternative was not to build it. The argument for building it anyway is that the graphs people
actually want — *tell me when this drops 5%*, *sell this if it goes under that*, *put $50 a week
into an index fund* — are exactly what a node graph is for, and the honest response to "the API is
unofficial" is to say so plainly and design around it, not to pretend it isn't.

## 2. Where it will break, and why that is one file

Everything that depends on the interface's shape is in **two** places:

- `RobinhoodApi` — every URL.
- `Orders` — every field of an order payload.

Plus the login state machine in `RobinhoodSession.acquireTokens`, which is the part most likely to
change, because authentication is where Robinhood actually invests.

Nothing else in the library knows a URL or a field name. That is deliberate and it is the main
structural decision here: when something moves, the fix should be an edit to a constant rather than
an archaeology exercise across nine node classes. `Json` exists for the same reason at the other
end — every field of every response is read through it, and a field that has vanished reads as null
rather than throwing `JSONException` out of a node.

The stub in `StubRobinhood` is what makes all of that checkable without an account, a second factor,
or a test suite that places orders. It is also what caught the first real bug in this library:
`java.net.http` refuses to set a `Connection` header, so the copied-from-Python header block made
every single call throw before it left the machine.

## 3. Logging in

Robinhood's password grant can answer four ways, and which one an account gets decides whether an
unattended graph can log in at all.

| Robinhood answers | This library does |
| --- | --- |
| tokens | nothing more |
| `mfa_required` | generates the code from the **MFA Secret** seed and retries, once |
| `verification_workflow` | starts the approval workflow and waits for the tap in the phone app |
| `challenge` (SMS, email) | fails, saying to switch to an authenticator app |

The last row is the interesting one. **A graph cannot read a text message**, and there is no
mechanism in HouseGraph by which a running node could ask a person for six digits and wait. So the
honest answer is to fail with the fix in the message rather than to half-support it.

The app-approval row is the one that *can* be automated even though it is interactive, because the
person acts on their phone rather than in the graph. It is polled to a deadline (**Approval Timeout
(s)**, 120 by default), so an unattended machine reconnecting at 4am fails cleanly rather than
hanging forever on a prompt nobody will answer.

**The device token is derived from the username, not random.** A random one per login makes every
connection look like a new phone, which asks for device approval *every single time* — turning the
one thing a person has to do once into a thing they have to do hourly. Deriving it keeps it stable
across restarts without this library writing anything to disk.

## 4. Secrets: the store holds them, the graph file never does

**Nothing in this library writes a credential or a token to disk.**

Credentials come from HouseGraph's built-in **Secret Loader** node — one wired into each of
**Username**, **Password** and **MFA Secret**, each pointing at a key in the host's encrypted secret
store. The Secret Loader saves the *key* and resolves the *value* fresh on every run, so a reloaded
graph connects with nothing typed in and nothing sensitive in the file. Tokens live in the session
object, in memory, for the life of the process: no cache file, no keyring entry, nothing under
`AppDirectories`.

Typing straight into the fields works too, and is quicker for trying something out. All three ports
are `markSecret()`, so a typed value is gone on reload where a wired one comes back.

**Username is marked secret even though a username is not much of a secret**, and the reason is
worth writing down because it is not obvious. A save file records a manually-editable input's
*current* value, and `NodeVariable.isPersistentValue()` is a flag set at construction — it cannot
tell a value somebody typed from one an edge resolved a moment ago. So an unmarked Username port
would take whatever the Secret Loader had just fetched and write it into the graph file, which is
precisely what fetching it from the store was meant to avoid. The same trap is waiting for any node
that accepts a credential on an ordinary input.

Writing the refresh token into the host's `SecretsStore` was considered and rejected: `sdk.Secrets`
is deliberately read-only, it is the seam a future per-library permission check would sit behind,
and a node library that writes to the credential store is exactly what that seam exists to be able
to say no to later. With Secret Loaders wired there is nothing to gain from it anyway — logging in
again is one round trip.

**Holding the TOTP seed collapses two factors into one.** It is worth it for a machine that trades on
its own and not worth it for one a person drives by hand, which is why `MFA Secret` is optional and
the app-approval path exists beside it.

**The account node still does not reconnect by itself on load.** With Secret Loaders wired it could
— the credentials would be there — so this is a choice rather than a limitation: a brokerage session
re-establishing itself the moment a file is opened is not a thing to do quietly. A graph that should
log itself in wires a startup trigger into **Connect**, where it is visible on the canvas.

## 5. Dry Run is on by default

**Place Order** ships with **Dry Run** switched on. A freshly dropped node works the whole order out
— instrument, share count, what it would cost — reports it, fires **Not Placed**, and sends nothing.

This is the decision most likely to look like the node is broken, so: the mistakes that are cheap
everywhere else in HouseGraph — a trigger wired to the wrong port, a loop that runs once per item
instead of once, a graph left running after a test — are not cheap here, and they all show up on the
*first* run of a half-built graph. Shipping ready-to-trade would make that first run the expensive
one. This way it tells you what it would have done.

`Max Order Value ($)` is the other rail, for graphs that are past the first run. It fails the node
rather than quietly skipping, because a graph that believes it bought something and didn't is worse
off than one that stopped and said why. An order whose value cannot be established fails it too: an
unchecked ceiling is not a ceiling.

## 6. Placing and checking are different nodes

An order can sit queued overnight, fill in pieces over an hour, or be rejected a second after
Robinhood accepted it — **Robinhood answers 201 and puts the refusal in the order's own state.** So
"did it go in?" and "did it work?" are different questions, and the second one has a schedule
attached.

Schedules belong to triggers ([`CLAUDE.md`](../../CLAUDE.md#node-design-control-vs-action)). A Place
Order node that waited for its own fill would hold a graph open for hours and would only ever work
on the one schedule built into it; **Order Status** driven by a repeating trigger asks as often as
the graph wants to know. The same rule puts Connect/Disconnect on the account node and nothing else:
that is a connection lifecycle, the rule's named exception, and it schedules nothing.

## 7. Two things about orders that surprise people

**A market order carries a price.** Robinhood's `market` type is not "fill at any price" — the API
requires a `price` and uses it as a *collar*, the worst price the order may accept. Sending none is
rejected. This library takes a quote moments before and pads it by 5% in the direction the order is
going. Too tight and an ordinary spread means the order never fills; too loose and a market order
placed into a fast move fills at a price nobody would have accepted. A graph that wants a promise
about price should place a limit order, which is the tool for it.

**Nothing retries.** A POST to `/orders/` that timed out may well have been received, and a client
that helpfully sent it again could buy the same stock twice. Every prepared order carries a
`ref_id` — Robinhood's own idempotency key — so re-sending *the same prepared order* is one order
rather than two, and the decision to re-send is left to a graph, where a person can see it
happening.

## 8. What this library does not do

Equities and ETFs only. **No options, no crypto, no transfers, no watchlists.** Options and crypto
are different endpoints with different payload shapes (crypto is a different host entirely), and
each would roughly double the surface that can break silently. Transfers move money between a bank
and a brokerage and are not something to bolt onto an unofficial client.
