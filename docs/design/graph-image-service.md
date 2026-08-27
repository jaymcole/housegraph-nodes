# The graph-image service

`housegraph-app`'s **Graph Images** node asks HouseGraph to draw the graph that is open and hands
the pictures to the rest of the graph. This note is the other half of that: **what HouseGraph has
to publish for the node to work**, and why the contract between them looks the way it does.

The node ships now and fails with a sentence until the application side exists. That is deliberate
— the shape below is small enough to read in one sitting, and pinning it down here is what lets
the two repositories be changed in either order.

---

## 1. What already exists

HouseGraph's **Export Images…** button writes a PNG per **connected component** of the canvas —
one picture per distinct automation in the file. `GraphComponents` does the splitting,
`GraphImageExport.exportComponents(canvas, directory, baseName)` does the drawing, and `App` is
its only caller. None of that needs to change; the service is a second caller of the same method.

## 2. Why a node cannot just call it

Two walls, and only one of them is about packaging:

- **The picture is of the views, not of the graph.** Where each node was dragged, how an edge
  curves, which colours the theme uses — none of it is in `NodeGraph`. It is in `GraphCanvas`,
  on the JavaFX thread.
- **A node library cannot see the application's classes, and never will.** `housegraph-api` is the
  entire shared vocabulary: `app`'s types are not on a library's compile classpath. Nor can the
  application see the library's — plugin class loading is parent-first (decision 0005), so a
  parent has no route to a child's classes.

The second wall is the awkward one, because it rules out the obvious contract. **An interface
declared on either side is useless**: one the library declares, the application cannot implement;
one `app` declares, the library cannot name.

## 3. The contract

What both sides *can* agree on is the JDK and `housegraph-api`. So:

> The application registers a **`java.util.function.Function<Map<String, Object>, Map<String, Object>>`**
> in **`ResourceRegistry.shared()`** under the name **`housegraph.graph-images`**.

`Function` and `Map` come from the bootstrap loader, so both sides mean the same classes by them.
The registry is already the API's answer to "reach something by name rather than by wiring", and
it already tolerates a name that nobody registered — which is exactly the state of every HouseGraph
released so far.

**Request** — the node always sends `directory` and `contract`, and sends `baseName` only when the
user set one:

| Key | Type | Meaning |
| --- | --- | --- |
| `directory` | `String` | Absolute, normalised path of an existing folder to write the PNGs into. The node creates it first, and owns what is in it afterwards. |
| `baseName` | `String` | Optional. What to name the files before the per-component suffix. **Absent means "you choose"** — fall back to the open graph's file name, as the button does. |
| `contract` | `Integer` | Revision of this contract, currently `1`. A service free to assume 1 may ignore it. |

**Reply** — one of two shapes:

| Key | Type | Meaning |
| --- | --- | --- |
| `files` | `List<String>` | Absolute path of each PNG written, **in the order the application numbered them** — by canvas position, top to bottom then left to right. Empty is a valid answer: a graph with no nodes has no components, and the node treats that as an empty list rather than a failure. |
| `error` | `String` | A sentence saying what went wrong, shown to the user as the reason. A blank value is not an error. |

Anything else — a null reply, a reply that is not a `Map`, a `files` that is not a `List` — fails
the node with "this library and that service disagree about the contract", which is the honest
description of a version skew that only a runtime check can catch.

## 4. What the application side has to do

```java
// Once, where the canvas exists and only when it does.
ResourceRegistry.shared().register("housegraph.graph-images",
        (Function<Map<String, Object>, Map<String, Object>>) this::drawGraphImages);

private Map<String, Object> drawGraphImages(Map<String, Object> request) {
    if (!(request.get("directory") instanceof String folder) || folder.isBlank()) {
        return Map.of("error", "no folder was given to write the images into");
    }
    String baseName = request.get("baseName") instanceof String name && !name.isBlank()
            ? name : exportBaseName();
    try {
        // exportComponents touches the scene graph, so it runs on the FX thread; the caller is a
        // node on a worker thread and blocks here until it is done.
        List<File> written = onFxThread(() ->
                GraphImageExport.exportComponents(canvas, new File(folder), baseName));
        return Map.of("files", written.stream().map(File::getAbsolutePath).toList());
    } catch (Exception e) {
        return Map.of("error", e.getMessage() == null ? e.toString() : e.getMessage());
    }
}
```

Four things that are easy to get wrong:

1. **Hop to the FX thread and block.** `process()` runs on a worker, and `exportComponents` walks
   the scene graph and calls `snapshot`. A `FutureTask` handed to `Platform.runLater` and waited on
   is the whole of it. If the calling thread is interrupted — the engine does that to cancel a run
   or enforce a node timeout — let the wait end and throw; the node turns that into "the run was
   cancelled" rather than repeating the application's complaint.
2. **Answer with `error`, do not throw.** A thrown exception is handled (the node reports it), but
   the message is better when the application writes it deliberately.
3. **Register only where there is a canvas.** Registering from `App` after the canvas is built is
   both necessary and sufficient. Do not register from a code path that might run without one —
   an absent name is a good failure, and a registered service that cannot draw is a bad one.
4. **Do not delete anything.** The caller passes a folder it owns and cleans up after itself.

## 5. What the node does with it

`GraphImagesNode` creates a temporary folder, asks, reads each PNG whole into a
`javafx.scene.image.Image`, then deletes the folder — so `Images` is the only copy, ready to be
posted, classified, or written somewhere by another node. Given a **Folder**, it writes there
instead and deletes nothing.

## 6. Where this goes next

**A typed SPI is the better long-term shape.** If `housegraph-api` grows, say,
`sdk.GraphImageExporter`, both sides can name it and the map disappears — that is a version bump
of the API and a one-class change behind `GraphImages`, with the node untouched. The map contract
is what makes the feature possible *without* an API release, not an argument against ever having
one.

Two extensions the request has room for, and deliberately does not have yet: a `scale` (the export
is 1:1 today) and a `components` filter (draw one automation rather than all of them). Both are new
optional keys, which a service that does not know them ignores — no revision bump needed.

**Reference:** HouseGraph `docs/engine/ui-layer.md` (image export), decision 0005 (parent-first
plugin class loading), decision 0009 (supervised graphs run in the real windowed app, which is why
this works on an unattended server).
