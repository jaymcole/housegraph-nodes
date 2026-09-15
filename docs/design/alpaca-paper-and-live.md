# Alpaca: paper, live, and the two switches between them

Why `housegraph-alpaca` ships pointed at fake money, what the second safety switch is for when the
first one already exists, and the handful of Alpaca-specific rules that shape the nodes.

---

## 1. Alpaca is the broker to automate against

Alpaca publishes a REST API for retail customers. It issues the keys, documents the endpoints,
states the rate limit, and — the part that decides everything below — runs a **paper-trading
account** alongside the real one.

Paper is not a simulator bolted on afterwards. It is the same API, at the same paths, answering with
the same shapes, priced off the same market data, with **its own separate key pair**. A graph built
against paper is the same graph that trades live; the only thing that changes is which keys it is
holding.

That is a materially different bargain from the one `housegraph-robinhood` makes, and the two
libraries are deliberately shaped the same way so that the difference is visible:

| | `housegraph-alpaca` | `housegraph-robinhood` |
| --- | --- | --- |
| Published API | yes | no — the private one its apps use |
| Can it stop working without notice | not without a deprecation notice | yes, overnight |
| Terms of service | automation is the point | very likely breached |
| Rehearsal account | yes, with its own keys | none |
| Reject reason on an order | not in the API | yes |

If the question is "which of these should I automate against", it is Alpaca. `housegraph-robinhood`
exists because somebody's money is already at Robinhood, not because it is the better interface.

## 2. Paper Trading is a switch that cannot be flipped by accident

**Alpaca issues a different key pair for the paper account and the live one, and each pair only
works against its own host.** That single fact is what this library's safety story rests on.

The **Alpaca Account** node's `Paper Trading` input chooses the host. It defaults to on. Turning it
off does *not* turn a paper graph into a live one — it makes the graph stop connecting, because the
paper keys it is holding are not accepted by the live host. Going live is a separate, deliberate act:
new keys, from a different page of Alpaca's dashboard, pasted in on purpose.

So the failure everybody worries about — *the test graph turned out to have been trading real
money* — is not merely unlikely here. It requires somebody to have gone and fetched live keys.

The node makes the consequence legible rather than leaving it in an input: a live session's status
line reads `Connected - LIVE account …` in capitals, and Place Order prefixes a live placement with
`LIVE:`. The most consequential fact about a graph on the canvas should be readable without opening
a node.

## 3. So why is there a second switch?

`Paper Trading` protects the **account**. `Dry Run` protects against the **graph being wrong**. They
are different failures and neither covers the other.

**Place Order** and **Close Position** ship with `Dry Run` on. A freshly dropped node works the whole
order out — the size, the price it would go in at, what it would cost — reports it, fires its
"nothing happened" branch, and sends nothing.

The reason is what these nodes are wired into. A flow port fires when something upstream says so, and
the mistakes that are cheap everywhere else in HouseGraph are not cheap here:

- a trigger wired to the wrong port,
- a loop that runs once per item where once was meant,
- a graph left running after a test,
- a branch whose condition is inverted.

On a **paper** account those cost nothing, which is exactly why `Dry Run` should come off early
there — rehearsing with it on is rehearsing nothing. On a **live** account the first run of a
half-built graph would be the expensive one, and the default makes that first run tell you what it
*would* have done instead.

There is a third guard for the unattended case: **Max Order Value ($)** fails the node outright for
an order estimated to be worth more than it. It fails rather than skipping quietly, because a graph
that believes it has bought something and hasn't is worse off than one that stops and says why. If
the order cannot be valued at all — the market-data feed reported no price — that also fails, because
an unchecked cap is not a cap.

## 4. Three Alpaca rules that shape the nodes

These look arbitrary from inside a graph, so they are validated in `OrderRequest` with a message
naming the fix, before anything is sent:

- **A dollar amount (`notional`) works only on a market order good for the day.** Alpaca sizes such
  an order at execution, which it can only do for an order taking the market price today. In
  exchange, "$100 of AAPL" really is $100 — there is no share count divided out beforehand and
  rounded, which is what the Robinhood library has to do.
- **An extended-hours order must be a limit order good for the day.** There is no continuous auction
  outside regular hours for a market order to fill against.
- **A trailing stop needs exactly one of Trail Price and Trail Percent.** Two trails would be two
  different stops.

Two more things that are Alpaca's shape rather than this library's choice:

- **Prices must be at the tick** — two decimal places at a dollar and above, four below it. Alpaca
  rejects a more precise limit price outright rather than rounding it, so `Orders` rounds before
  sending. The rounded price is what a dry run reports, so it is visible before anything goes.
- **An order carries a unique `client_order_id`**, which Alpaca refuses to reuse. A re-sent request
  cannot become a second trade. Note the difference from an idempotency key that replays the original
  answer: Alpaca's refusal surfaces as a failed node, not a success. The trade not happening twice is
  the part that matters, and nothing in this library retries on its own anyway — a POST that timed
  out may well have been received.

## 5. The market-data feed, and why the default is the quiet one

Market data comes from a third host (`data.alpaca.markets`), the **same one for paper and live**,
because quotes are not account state. Paper keys read it perfectly well, which is what makes it
possible to build a whole strategy without a funded account.

The `Feed` input defaults to **`iex`** because that is the feed every Alpaca account has. The
consolidated tape (`sip`) needs a paid market-data subscription. The trade-off is real and worth
stating:

- IEX is **one exchange**. Its last trade can lag the consolidated one slightly, its volume is a
  fraction of the market's, and a thinly traded symbol can be quiet there all day.
- That is fine for *has this moved 3% today?* and misleading for *exactly what did it just trade
  at?*.

Asking for `sip` without a subscription **fails the node** with a message saying so, rather than
falling back to IEX. That is the right way round: a strategy silently reading a different feed than it
thinks it is reading is worse than one that stops.

`Get Quote` reads Alpaca's *snapshot* endpoint rather than its quote endpoint, because
`/quotes/latest` returns only a bid and an ask — no last trade, no previous close — so a node built
on it could not answer "what is it worth?" or "is it up today?". The snapshot returns all of it in
one call. `Quote.price()` then answers the last trade when there is one, the midpoint of the bid and
ask when there isn't, and today's close as a last resort, which is what makes it sensible at 3am as
well as at noon.

## 6. What Alpaca will not tell you

**There is no reject reason on an order.** When the exchange turns an order down, Alpaca moves it to
`rejected` and the explanation goes out over its trade-update stream — which this library does not
hold open. So `Order Status` reports `rejected` and that is all there is; Alpaca's own dashboard is
where the reason is.

The other kind of refusal *is* explained: an order Alpaca will not accept at all (bad symbol, not
enough buying power) comes back as a 422 whose message says why, and that fails the Place Order node
with Alpaca's own words. It never becomes an order, so there is nothing to look up afterwards.

This is worth knowing before building a graph that expects to be told why something failed. It is
also the one place the Robinhood library reports *more* than this one.

## 7. Where it would break, and why that is two files

Everything that depends on the API's shape is in two places:

- `AlpacaApi` — every URL and query parameter.
- `Orders` — every field of an order payload.

A moved endpoint or a renamed field is an edit in one of those rather than an archaeology exercise
across a dozen node classes. Unlike the Robinhood library, this is precaution rather than
expectation: Alpaca versions its API and deprecates with notice. It has still moved things — the
account response lost `pattern_day_trader`, `daytrade_count` and `daytrading_buying_power` in July
2026 when US intraday margin rules changed — which is why every field in this library is read through
`Json`, answering null for "not there" rather than throwing.

## 8. What is not here

- **Crypto and options.** Alpaca trades both. The nodes are scoped to US equities and ETFs, which is
  what the symbols, the market clock and the order shapes all assume.
- **Bracket, OCO and OTO orders.** A take-profit-and-stop pair placed as one order is genuinely
  useful and is a larger design question: it is two prices and two legs on a node that already has
  fourteen inputs. A graph can express the same thing today with a Place Order and an Order Status
  node on a trigger.
- **The streaming API.** Alpaca pushes trade updates and live quotes over websockets. That is a
  resource node with a connection lifecycle and a very different testing story; polling with a
  repeating trigger is what this library does instead, and it is enough for anything checking on a
  human timescale.
- **Auto-connect on load.** The Alpaca Account node does not reconnect by itself when a graph is
  opened, even though with Secret Loaders wired it could. A brokerage session re-establishing itself
  the moment a file is opened is not a thing to do quietly. Wire a startup trigger into `Connect` for
  a graph that should connect itself — the same choice `housegraph-robinhood` makes.
