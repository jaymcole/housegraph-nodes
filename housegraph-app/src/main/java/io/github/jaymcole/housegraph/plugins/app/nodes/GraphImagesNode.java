package io.github.jaymcole.housegraph.plugins.app.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugins.app.GraphImages;
import io.github.jaymcole.housegraph.plugins.app.HostServiceException;
import javafx.scene.image.Image;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Asks HouseGraph to draw the graph that is open and puts <b>one image per connected component</b>
 * on <b>Images</b> — a picture of each distinct automation in the file, the same set of pictures
 * the application's own <b>Export Images…</b> button writes.
 * <p>
 * <b>Components, not one big picture.</b> A save file usually holds several automations that share
 * a canvas but nothing else; HouseGraph splits on that and draws each alone, so a graph with a
 * doorbell flow and a nightly backup flow gives two images, not one with both and a gap between
 * them.
 * <p>
 * <b>The order follows the canvas, not the file.</b> Components arrive numbered by where they sit
 * — top to bottom, then left to right — so the list matches how you would count them looking at
 * the graph, and does not shuffle when a save reorders the nodes underneath. Drag one automation
 * above another and they swap places, which is the trade for numbering that means something to
 * someone reading the picture.
 * <p>
 * <b>Leave Folder empty and nothing is left behind.</b> The images are drawn into a temporary
 * folder, loaded, and the folder deleted — Images is then the only copy, which is what you want
 * when the next node posts them to Discord or hands them to a classifier. Set <b>Folder</b> to
 * keep the PNGs: they are written there, named after the open graph unless <b>Base Name</b> says
 * otherwise, and nothing is deleted.
 * <p>
 * <b>It works on a server too.</b> The picture comes from the canvas — where each node was
 * dragged, how the edges curve, the theme's colours — and a graph the remote daemon supervises
 * runs in the ordinary windowed application, one child JVM per graph, so an unattended machine can
 * keep a dated picture of what it is actually running. What the node cannot do is draw where there
 * is no canvas at all: a HouseGraph too old to offer the service, or a future canvas-free runner,
 * fails the run with a message naming both possibilities rather than emitting an empty list. See
 * {@link GraphImages}.
 * <p>
 * <b>An empty graph is not a failure.</b> A file with no nodes has no components, so Images is an
 * empty list and the flow carries on — the same rule the rest of this repository follows for "the
 * question was asked and the answer was nothing".
 * <p>
 * <b>One drawing at a time.</b> The node ships with a concurrency limit of one. Drawing happens on
 * the application's UI thread and a large graph is tens of megabytes of pixels, so two runs
 * arriving together are better queued than interleaved.
 */
@Display.Name("Graph Images")
@Display.Description("Asks HouseGraph to draw the open graph, and outputs one image per connected component.")
@Node.Kind(NodeKind.ACTION)
@Node.Keywords({"graph", "image", "images", "picture", "screenshot", "snapshot", "export", "draw",
        "render", "png", "component", "canvas", "diagram", "housegraph"})
@Node.Type("app.GraphImagesNode")
public class GraphImagesNode extends BaseNode {

    private static final Logger log = Log.get(GraphImagesNode.class);

    /**
     * The type the Images port declares. A data port's type is a bare {@link Class}, so a list port
     * is {@code List.class} with its element type erased; laundering it through {@code Class<?>}
     * once here is the same move {@code housegraph-collections} makes, and keeps the unchecked
     * suppression out of the field.
     */
    @SuppressWarnings("unchecked")
    private static final Class<List<?>> LIST = (Class<List<?>>) (Class<?>) List.class;

    /** The temporary folder's name, so an interrupted run leaves something recognisable behind. */
    private static final String TEMP_PREFIX = "housegraph-graph-images-";

    private final NodeVariable<String> folder = new NodeVariable<>("Folder", String.class, true);
    private final NodeVariable<String> baseName = new NodeVariable<>("Base Name", String.class, true);

    private final NodeVariable<List<?>> images = new NodeVariable<>("Images", LIST);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    public GraphImagesNode() {
        // Drawing is the application's UI thread rendering a canvas the size of the graph - see the
        // class documentation. The engine queues a second run on this node's permit rather than
        // asking for two renders at once.
        setMaxConcurrency(1);
    }

    @Override
    public void process(ProcessContext ctx) {
        String requested = folder.getValue();
        boolean temporary = requested == null || requested.isBlank();
        Path directory = temporary ? temporaryFolder() : folderNamed(requested.trim());
        try {
            // The last cheap moment to notice a superseded or cancelled run: what follows is one
            // blocking call into the application and then a read of everything it wrote.
            ctx.checkCancelled();
            List<Path> files = GraphImages.export(directory, baseName.getValue());
            List<Object> loaded = new ArrayList<>(files.size());
            for (Path file : files) {
                loaded.add(load(file));
            }
            images.setValue(List.copyOf(loaded));
        } finally {
            if (temporary) {
                deleteRecursively(directory);
            }
        }
    }

    /**
     * One PNG, read whole into memory.
     * <p>
     * Read from a stream rather than handed to {@code new Image(url)} deliberately: that reads the
     * pixels here and now, so the file can be deleted the moment this returns, which is what makes
     * the temporary-folder case leave nothing behind. {@link Image} reports a decode failure on the
     * object instead of throwing, so it is checked rather than trusted.
     */
    private static Image load(Path file) {
        try (InputStream stream = Files.newInputStream(file)) {
            Image image = new Image(stream);
            if (image.isError()) {
                throw new HostServiceException("Could not read the image HouseGraph wrote at " + file + ": "
                        + describe(image), image.getException());
            }
            return image;
        } catch (IOException e) {
            throw new HostServiceException("Could not read the image HouseGraph wrote at " + file + ": "
                    + e.getMessage(), e);
        }
    }

    private static String describe(Image image) {
        Exception failure = image.getException();
        return failure == null || failure.getMessage() == null ? "it is not a readable image"
                : failure.getMessage();
    }

    /**
     * The folder the Folder input names. It is text a user typed or an upstream node built, so it
     * is not necessarily a path this machine can express at all — a Windows folder wired into a
     * graph running on Linux, or a stray NUL — and the failure for that says so rather than
     * arriving as an {@code InvalidPathException} from inside the node.
     */
    private static Path folderNamed(String folder) {
        try {
            return Path.of(folder);
        } catch (InvalidPathException e) {
            throw new HostServiceException("\"" + folder + "\" is not a folder name this machine can use: "
                    + e.getMessage(), e);
        }
    }

    private static Path temporaryFolder() {
        try {
            return Files.createTempDirectory(TEMP_PREFIX);
        } catch (IOException e) {
            throw new HostServiceException("Could not make a temporary folder for the graph images: "
                    + e.getMessage() + " Set Folder to write them somewhere of your choosing instead.", e);
        }
    }

    /**
     * Removes the temporary folder and everything the application wrote into it.
     * <p>
     * Never fails the node: the images are already in hand by the time this runs, and a run that
     * succeeded should not be reported as failed because a file could not be unlinked. What is left
     * behind is a directory named {@value #TEMP_PREFIX}something under the system temporary
     * directory, which the operating system clears.
     */
    private static void deleteRecursively(Path directory) {
        try (var entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("Could not delete {}: {}", path, e.toString());
                }
            });
        } catch (IOException e) {
            log.debug("Could not clean up the temporary folder {}: {}", directory, e.toString());
        }
    }

    @Override
    public void configureInputs() {
        addInput(folder);
        addInput(baseName);
    }

    @Override
    public void configureOutputs() {
        addOutput(images);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }
}
