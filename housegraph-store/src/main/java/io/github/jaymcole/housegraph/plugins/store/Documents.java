package io.github.jaymcole.housegraph.plugins.store;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/**
 * Reading and writing one named entry inside a JSON document, as text. The document is the string
 * a {@code JsonDocumentStore} holds; everything here is a pure {@code String} &rarr; {@code String}
 * function of it, so the store I/O and the locking stay in the node and the entry handling stays
 * testable without a file.
 * <p>
 * <b>Values are text, both directions.</b> A stored value is written as a JSON string and read back
 * with {@link String#valueOf}, so a document someone else wrote — by hand, or through the web
 * server node's {@code /api/data} — still reads: {@code {"count": 3}} comes back as {@code "3"}.
 * Parse it downstream if you need a number. Storing structured values is not this class's job; the
 * store holds one document and a graph that wants a list has list nodes for that.
 * <p>
 * <b>Keys are flat top-level names, dots and all.</b> {@code "dinner.lastPayer"} is one entry
 * called {@code dinner.lastPayer}, not a path into a nested object. Nested traversal would be a
 * second syntax to learn and to escape, and a flat namespace is enough for what a graph remembers.
 * <p>
 * The document is re-serialised indented, because it is a file a person may well open and edit —
 * and because the web server node can serve it, where a one-line blob is nobody's friend.
 */
public final class Documents {

    /** Spaces per level when re-serialising. See the class documentation for why this isn't compact. */
    private static final int INDENT = 2;

    private Documents() {
    }

    /**
     * The entry's value as text, or null when the document has no such entry (or holds JSON null
     * there, which is the same "nothing stored" as far as a graph is concerned).
     */
    public static String read(String document, String key) {
        JSONObject root = parse(document);
        if (key == null || !root.has(key)) {
            return null;
        }
        Object value = root.get(key);
        return value == JSONObject.NULL ? null : String.valueOf(value);
    }

    /** The document with {@code key} set to {@code value}, replacing any previous entry. */
    public static String with(String document, String key, String value) {
        Objects.requireNonNull(key, "key");
        // org.json treats a null value as "remove this key", which would make a Set with nothing
        // wired silently behave like a Clear. The node gates null before it gets here; this is the
        // second lock on that door.
        Objects.requireNonNull(value, "value");
        return parse(document).put(key, value).toString(INDENT);
    }

    /** The document with {@code key} gone. Removing an entry that isn't there is not an error. */
    public static String without(String document, String key) {
        JSONObject root = parse(document);
        root.remove(key);
        return root.toString(INDENT);
    }

    /**
     * An empty or missing document reads as an empty object rather than failing — a store nobody
     * has written to yet is the normal first run, not a problem.
     * <p>
     * A document that parses as something other than an object (the store also accepts a JSON
     * <em>array</em>) fails loudly instead. Quietly replacing it with an object would throw away
     * whatever else is in there, which is the worse of the two outcomes by a distance.
     */
    private static JSONObject parse(String document) {
        if (document == null || document.isBlank()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(document);
        } catch (JSONException e) {
            throw new IllegalStateException(
                    "This data store does not hold a JSON object, so it has no named entries to read or write: "
                            + e.getMessage(), e);
        }
    }
}
