# Local database storage

A design note, not a built library. It proposes how HouseGraph should store **many records**
locally, and answers the question that actually decides the shape of the thing: *what happens when
someone wants to change a table's schema?*

---

## 1. Where `Stored Value` stops

`housegraph-store` holds **one JSON document per named store**, as a flat map of text entries
(`Documents`). That is exactly right for what it was built for: "who paid for dinner last",
"is the porch light on", "when did the sync last run". It is a variable that outlives a restart.

It stops working the moment there is more than one of something:

| What you want | What the document store does |
| --- | --- |
| Append a reading every minute | Read the whole document, rewrite the whole document, per row |
| "Readings from the last hour" | No query. Read everything into the graph, filter with list nodes |
| "Update the chore whose name is X" | Read all, find, rewrite all |
| Two writers at once | `JsonDocumentStore` serialises whole-document replacement — last writer wins |
| 50k rows | 50k rows of JSON re-serialised and fsynced on every single write |

Every one of those is the same failure: **the unit of read and write is the entire dataset.** That
is fine for a value and quadratic for a table. Nothing about `Documents` can be tuned out of it —
the fix is a different storage engine underneath, not a cleverer key syntax on top.

## 2. What "records" actually needs

Working backwards from what a home graph does — a sensor log, a chore list, a guest list, a
message archive, a tally per person per day:

- **Append** one record cheaply, at any rate, without touching the others.
- **Query a subset** by a condition, with ordering and a limit, without loading the rest.
- **Update / delete** matching records.
- **Count and aggregate** without pulling rows into the graph.
- **Survive** restart, crash mid-write, and two writers in the same tick.
- **Be inspectable and repairable by hand** — this is someone's house, and their six months of
  sensor history. If the file becomes unreadable, it needs to be openable by a normal tool.

## 3. Engine: SQLite, via `sqlite-jdbc`

Recommendation: a new library, `housegraph-database`, bundling
[`org.xerial:sqlite-jdbc`](https://github.com/xerial/sqlite-jdbc).

**What was considered and why it loses:**

- **A JSON array in the existing document store.** Zero new dependencies, and genuinely fine to a
  few thousand rows — but it keeps the whole-dataset read/write that is the problem, has no index,
  no transaction, and no query. It buys a month and then has to be replaced with the same
  migration pain, plus a bespoke on-disk format nobody else can read.
- **H2 / HSQLDB.** Pure Java, so they relocate cleanly and bundle small — a real advantage under
  build rule 2. Against them: their on-disk formats change between versions (an H2 1.x→2.x upgrade
  cannot read the old file without an export/import dance). That is *the library upgrade eating the
  user's data*, which for a monorepo that re-releases every library on every API bump is an
  unacceptable class of bug.
- **DuckDB.** Analytical column store, ~50MB of natives, tuned for scans over a million rows.
  Wrong shape and wrong size for "did the back door open today".
- **SQLite.** The file format is explicitly stable-forever, it is the single most-deployed database
  in the world, every desktop OS ships tooling that can open the file, and it does exactly the
  right things for this workload: single file, ACID, WAL concurrency, indexes, and — the point
  section 5 turns on — **dynamic typing**.

**Build consequences** (against `docs/shared/node-library-rules.md`):

- `sqlite-jdbc` **does** depend on `slf4j-api` (1.7.36, compile scope), so rule 6 applies and the
  dependency needs an `exclude` — checked with `./gradlew :housegraph-database:dependencies`, which
  is how the other three were caught, and worth doing rather than assuming: the first draft of this
  note asserted the opposite from memory.
- It registers a JDBC driver by `ServiceLoader`, so `mergeServiceFiles()` — already unconditional
  in the convention plugin — is load-bearing (rule 3 names JDBC drivers for this reason).
- **Do not relocate it, deliberately, and write down why.** The jar loads a native library by
  extracting it from a resource path derived from its own package name. Relocation rewrites both
  the classes and the resource paths, and whether the string constants that build that path get
  rewritten consistently is a property of the shading tool, not of anything we control. This
  repository already made exactly this call for DJL in `housegraph-ml/build.gradle` — "nothing to
  collide with yet, native loading makes blind relocation risky for no present benefit, revisit if
  a second library bundles it". Same reasoning, same note, same revisit condition.
- Get the connection from the driver class **directly**, not via `DriverManager`. `DriverManager`
  resolves `jdbc:sqlite:` against every registered driver in the shared class loader; if a second
  installed library ever bundles SQLite too, which of the two answers is a coin toss. Naming the
  driver we bundled removes the question.
- Bundle size ≈ 12 MB (natives for every platform in one artifact — there is no per-OS classifier).
  That is a jar in a release next to `housegraph-ml`'s DJL; it is not a problem, but it is the
  reason this is its own library rather than nodes added to `housegraph-store`, which today bundles
  nothing and should stay that way.

## 4. Shape on the canvas

**Mirror the shape that already works.** `Data Store` → wired into `Stored Value` nodes that each
name a *Key*. So: a **Database** resource node → wired into action nodes that each name a *Table*.

```
[Database "house"] ──Db──┬──> [Insert Row]  Table: readings   Row: {…}      → Id
                         ├──> [Find Rows]   Table: readings   Where…        → Rows, Count
                         ├──> [Update Rows] Table: chores     Where…, Set…  → Updated
                         ├──> [Delete Rows] Table: chores     Where…        → Deleted
                         └──> [SQL Query]   SQL + ? params                  → Rows
```

- **`Database`** is a resource node that owns a connection lifecycle — the *named exception* in the
  control-vs-action rule, the same one `Web Server` and `Discord Bot` take. It outputs a live handle
  as a `transientValue()`, exactly as `DataStoreNode` outputs its `JsonDocumentStore`. Identity is
  the **name**, and the name is all the graph save holds, so deleting the node and recreating it
  with the same name reopens the same data rather than stranding it. Suggested path:
  `AppDirectories.get().dataStore(name).resolve("database.db")` — reuses the host's sanitiser and
  its "one named store, one folder" convention, and the `Open folder` button gets you to a file you
  can double-click.
- **Everything else is a plain action node**: flow-in, does one thing for that one invocation,
  reports the outcome. `Find Rows` may have a second flow-out for "no matches" — that is a
  per-invocation outcome, the same thing `Git Sync`'s `Pulled` is, not a branch it schedules.
  **No node here owns a timer.** A row written every minute is a repeating trigger wired into
  `Insert Row`.
- **A row is a `Map`, a result set is a `List` of `Map`s.** No new value types, and the entire
  `housegraph-collections` library — `Build Map`, `Map Get`, `Get Item`, `List Count`, `Tally`,
  `Format Each` — already operates on both. That reuse is worth more than any bespoke `Row` type
  would be.
- **`Where` and `Set` grow by wiring**, the way `Build Map` grows its pairs: a column name field, an
  operator picker, a value input, and a fresh empty row appears when you wire the last one.
  Operators that cover the realistic cases: `=`, `≠`, `<`, `≤`, `>`, `≥`, `contains`, `starts with`,
  `is empty`. Anything past that is what the SQL node is for.
- **`SQL Query` / `SQL Statement` is the escape hatch, and it is parameterised.** `?` placeholders
  with wired values, never string concatenation. A graph where a Discord message reaches a query is
  the normal case here, not the exotic one, and the node must make the safe form the only form.

## 5. Schemas — the question this all turns on

> *If the user wants to change the schema, how could we support that?*

The answer has three parts, and the first one does most of the work.

### 5.1 Additive change is free, and should be automatic

**SQLite is dynamically typed.** A column's declared type is an *affinity*, a hint — a value keeps
its own storage class, and a column declared with no type accepts anything. So the migrations that
hurt in Postgres mostly do not exist here:

- Adding a column is `ALTER TABLE t ADD COLUMN "humidity"`, which is a **metadata-only, O(1)**
  change: no table rewrite, whatever the row count. Existing rows read the new column as `NULL`.
- There is no "wrong type in this column" to migrate away from, because the column has no type to
  be wrong about.

So: **infer columns on write.** `Insert Row` is handed a map; any key it has not seen becomes a
column, added in the same transaction as the insert. The user who decides after two months of
logging temperature that they also want humidity wires one more pair into `Build Map` and it works,
with no migration step, no dialog, and no downtime. Their two months of temperature rows keep their
history and read humidity as absent.

**A `NULL` reads back as an absent map entry**, not as an empty string and not as a null object.
That makes `Map Get`'s found/not-found do the same job `Stored Value`'s `Found` output does, and for
the same reason: "recorded nothing" and "recorded the empty string" must stay distinguishable.

**Values keep their types across the boundary.** A number wired in is stored as a number and comes
back as one, so `ORDER BY` sorts numerically and `SUM` means something. This is the one place to
*not* copy `Documents`' text-in/text-out rule — that rule exists because a stored value is nearly
always about to be pasted into a message, whereas a column is about to be sorted, compared and
summed.

### 5.2 Renames and drops are explicit, manual, and backed up

The remaining changes are the genuinely destructive ones, and they are rare:

| Change | Mechanism | Cost |
| --- | --- | --- |
| Add column | `ALTER TABLE … ADD COLUMN` (automatic, §5.1) | O(1), no ceremony |
| Rename column | `ALTER TABLE … RENAME COLUMN a TO b` (SQLite ≥ 3.25) | O(1), data preserved |
| Drop column | `ALTER TABLE … DROP COLUMN` (SQLite ≥ 3.35) where permitted, else table rebuild | Data in that column is gone |
| Change type / add constraint | The standard 12-step rebuild: create new table, `INSERT … SELECT`, drop, rename, inside one transaction | O(rows) |
| Rename / drop table | `ALTER TABLE … RENAME TO`, `DROP TABLE` | — |

Three rules make these safe:

**(a) A migration is an editor action, never a side effect of a graph run.** It happens when a
person clicks it in the `Database` node's column editor. The alternative — a node that reconciles a
declared schema on `process()` — is the dangerous design: it re-evaluates on every tick, so a typo
in a column name silently drops a column at 3am on a timer, over and over, with no one watching. The
node that *runs* must only ever add columns (§5.1); the node that *destroys* must be driven by a
click.

**(b) Copy the file before any rebuild or drop.** `database.db` → `database.backup-<timestamp>.db`
in the same folder, before the transaction, always, unconditionally. It costs a file copy of
something that is usually a few megabytes, and it converts "I renamed the wrong field and lost six
months of sensor history" from unrecoverable into a rename in a file manager. This is the single
highest-value line of code in the whole design.

**(c) Show the blast radius before doing it.** The editor knows the row count and how many of those
rows have a non-null value in the column being dropped. "Drop `notes`? 4,812 of 5,001 rows have a
value" is a question someone can answer. "Are you sure?" is not.

### 5.3 The schema lives in the database file, not in the graph save

This is the part that is easy to get wrong and expensive to undo.

The `Database` node's `saveState()` holds **the store name and nothing else** — the same discipline
`DataStoreNode` follows. The schema is discovered from the file (`PRAGMA table_info`,
`sqlite_master`), and the library's own bookkeeping lives in the file too: `PRAGMA user_version` for
the internal layout version, plus a `_housegraph_meta` table recording the library version that last
touched it and a log of applied migrations.

Why not put the declared schema in the graph save, which is the obvious first instinct:

- **The file outlives the graph.** Nodes get deleted and recreated; graphs get copied between
  machines; the same database is opened by two graphs. A schema in the save is a second, disagreeing
  source of truth the moment any of that happens.
- **Loading an old save would "restore" a stale schema over live data.** Open last month's graph
  file and it now believes the table has the columns it had last month — and if the node reconciles,
  it undoes a month of change. A schema in the file cannot do this.
- **The file is editable from outside.** Someone will open it in DB Browser for SQLite, add an
  index, fix a typo'd row. That should just work, and with inferred columns and file-resident schema
  it does. A graph-resident schema would fight it.

### 5.4 Strict mode, opt-in

Inference is the right default and the wrong-only-sometimes one: a graph with a typo'd column name
(`tempurature`) silently grows a second column and half the readings go somewhere nobody queries.

So offer a per-table **Strict columns** toggle, default **off**. On, an insert naming an unknown
column *fails loudly* instead of adding it, and the columns are whatever the editor declared. It is
the same trade `Stored Value` makes with its `required()` inputs — forgiving by default, strict
where the cost of a silent mistake is high — and it is one checkbox, not a schema language.

## 6. Concurrency and durability

- **One `Database` instance per file, process-wide**, cached by normalised absolute path — copy
  `DocumentStores.forFile` exactly. Two nodes naming the same database must share one object, or
  their connection state and transactions diverge.
- `PRAGMA journal_mode = WAL` (concurrent readers alongside one writer),
  `PRAGMA busy_timeout = 5000`, `PRAGMA synchronous = NORMAL` (safe under WAL, and much kinder to an
  SD card in a Pi than `FULL`).
- Every node's work is one transaction. Unlike the JSON store, this genuinely survives two writers
  in the same tick — which is the point.
- **Teardown split**, per the rules: unregister listeners in `onRemoved()`; close the connection and
  checkpoint the WAL in `releaseResources()`. Both idempotent.

## 7. Suggested first slice

Small enough to prove the risky parts, useful on its own:

1. `Database` node (name → file, connection, `Open folder`, row/table summary) + `Databases.forFile`.
2. `Insert Row` with inferred columns, `Find Rows` with a growing `Where`, `Count Rows`.
3. `Delete Rows`, `Update Rows`.
4. The column editor on the `Database` node: rename, drop, with the backup copy and the blast-radius
   count.
5. `SQL Query` / `SQL Statement`, parameterised.

Steps 1-2 are built. **Prove the build before writing node four** — done for what exists: the
shaded jar is loaded in a child class loader over a parent holding the API, exactly as the host
loads a library, and driven by reflection to open a database, insert and read back
(`scripts/` has no home for this yet; it currently lives outside the repository, and giving it one
is the obvious next chore). That check covers the two failures that cannot happen in a unit test:
the bundled SQLite native not loading from inside the shaded jar, and a node resolving its own copy
of `BaseNode` instead of the host's — the second of which makes a node silently never appear.

Tag the PR **`#minor`** (a new library, backwards compatible).

## 8. Open questions

1. **An automatic `id` column** — an `INTEGER PRIMARY KEY` exposed on `Insert Row`'s output, so
   `Update`/`Delete` can address one exact row without a query. Recommended: yes, always.
2. **An automatic `created_at`** — home data almost always wants it and people almost always forget
   to wire it. Recommended: yes for new tables, epoch millis, documented, and nothing stops a user
   writing their own timestamp column instead.
3. **One file with many tables** (as proposed) **vs one file per table.** One file keeps joins and
   multi-table transactions possible and matches the `Data Store` → `Key` shape. Per-table files
   would make each table independently deletable. Recommended: one file.
4. **Do the SQL nodes ship in v1?** They are the smallest nodes to write and the largest support
   surface. Recommended: yes, but last, and documented as the escape hatch rather than the
   headline.
5. **Should `housegraph-store` get a "Rows" node that reads a table?** Tempting for discoverability;
   rejected here, because it would put a 12 MB dependency into the library that currently bundles
   nothing.
