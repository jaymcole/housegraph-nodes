package io.github.jaymcole.housegraph.plugins.app;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asking HouseGraph to draw the graph it currently has open, and getting back one PNG per
 * <b>connected component</b> — one picture per distinct automation in the file, which is the unit
 * the application's own <b>Export Images…</b> button works in.
 * <p>
 * <b>Why the drawing cannot happen here.</b> A picture of a graph is a picture of its
 * <em>views</em>: where the user dragged each node, which edges curve where, the colours of the
 * current theme. None of that is in the graph model — it lives in the canvas, which is the
 * application's, on the JavaFX thread, and is not reachable from a node library at all. So this
 * asks, and the application draws. Everything below is the asking.
 * <p>
 * <b>The files are the return value, not the point.</b> The service writes PNGs into a directory
 * because that is what it already knows how to do and because a large graph is tens of megabytes
 * of pixels that has no business being copied through a map. Whoever passed the directory owns
 * what is in it afterwards — {@code GraphImagesNode} passes a temporary one and deletes it
 * once the images are loaded, and passes the user's folder untouched when there is one.
 * <p>
 * The request and reply keys below, and what the application side has to do to serve them, are
 * written down in {@code docs/design/graph-image-service.md}.
 */
public final class GraphImages {

    private static final Logger log = Log.get(GraphImages.class);

    /**
     * The name the application publishes this service under. A dotted, application-owned name
     * rather than a bare one: {@link io.github.jaymcole.housegraph.resource.ResourceRegistry} is
     * also where a user's own resource nodes register themselves under names they typed, and those
     * are things like {@code "Kitchen bot"}.
     */
    public static final String SERVICE_NAME = "housegraph.graph-images";

    /** Request key: the directory to write the PNGs into, as an absolute path. Required. */
    public static final String DIRECTORY_KEY = "directory";

    /**
     * Request key: what to name the files, before the per-component suffix. Optional — the
     * application falls back to the open graph's own file name, which is what its Export Images…
     * button uses.
     */
    public static final String BASE_NAME_KEY = "baseName";

    /** Reply key: the absolute path of each PNG written, in the order HouseGraph numbered them. */
    public static final String FILES_KEY = "files";

    private GraphImages() {
    }

    /**
     * Draws the open graph and returns the file written for each of its components.
     *
     * @param directory where the PNGs should be written; taken as absolute before it is sent,
     *                  created if it does not exist, and left in place afterwards — deleting them
     *                  is the caller's business
     * @param baseName  what to name the files, or null/blank to let HouseGraph name them after the
     *                  open graph
     * @return one path per connected component, numbered by where it sits on the canvas — top to
     *         bottom, then left to right; empty when the open graph has no nodes at all, which is
     *         not a failure
     * @throws HostServiceException if this HouseGraph does not offer the service, if it could not
     *                              draw, or if it named a file that is not there afterwards
     */
    public static List<Path> export(Path directory, String baseName) {
        if (directory == null) {
            throw new HostServiceException("No folder to write the graph images into.");
        }
        HostService service = HostService.find(SERVICE_NAME).orElseThrow(GraphImages::notOffered);
        createDirectory(directory);

        Map<String, Object> request = new LinkedHashMap<>();
        // Absolute and tidied, because a folder typed into a node is text and may be neither: the
        // application resolves it against its own working directory, not the graph's, and it puts
        // what it was given in the log and in its own failure messages.
        request.put(DIRECTORY_KEY, directory.toAbsolutePath().normalize().toString());
        if (baseName != null && !baseName.isBlank()) {
            request.put(BASE_NAME_KEY, baseName.trim());
        }

        List<String> written = service.stringsOf(service.call(request), FILES_KEY);
        List<Path> files = new ArrayList<>(written.size());
        for (String path : written) {
            Path file = Path.of(path);
            if (!Files.isReadable(file)) {
                throw new HostServiceException("HouseGraph reported writing " + file
                        + ", but there is no readable file there.");
            }
            files.add(file);
        }
        log.debug("HouseGraph drew {} component image(s) into {}", files.size(), directory);
        return List.copyOf(files);
    }

    /**
     * The message for the case that is not a fault: this application does not publish the service.
     * <p>
     * Both of the reasons it might not are named, because the reader's next move differs entirely
     * between them and nothing observable from here tells them apart — an application too old to
     * have the feature and one that cannot have it are both simply an absent name in the registry.
     * <p>
     * Note which reason is <em>not</em> listed. A graph the remote daemon supervises runs in the
     * ordinary windowed application, one child JVM per graph — that is HouseGraph's decision 0009,
     * and it means an unattended server can draw its own graphs perfectly well. The second reason
     * above is the canvas-free runner that decision contemplates and does not yet have.
     */
    private static HostServiceException notOffered() {
        return new HostServiceException("This HouseGraph is not offering \"" + SERVICE_NAME
                + "\", so there is nothing here that can draw the graph. Either it predates the service,"
                + " or it is a build that runs graphs without a canvas - and the picture is of the canvas.");
    }

    private static void createDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new HostServiceException("Could not create the folder " + directory
                    + " to write the graph images into: " + e.getMessage(), e);
        }
    }
}
