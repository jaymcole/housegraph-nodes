package io.github.jaymcole.housegraph.plugins.database.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.annotations.NodeKind;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugins.database.Database;
import io.github.jaymcole.housegraph.plugins.database.Databases;
import io.github.jaymcole.housegraph.storage.AppDirectories;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A local database, exposed to the graph as a <b>data output</b> — wire its <b>Database</b> output
 * into <b>Insert Row</b> and <b>Find Rows</b>, so which nodes share which database is visible on the
 * canvas rather than matched up by name in two places.
 * <p>
 * <b>Identity is the name.</b> The database is one SQLite file at
 * {@code data-stores/<name>/database.db}, keyed by the name typed here, so the data is
 * <em>recoverable</em>: deleting this node and adding another with the same name reopens the
 * existing records rather than stranding them under an opaque id. Renaming points the node at a
 * different database; the old one stays on disk under its name and comes back if you type that name
 * again. That is the same rule the <b>Data Store</b> node follows, and the folder is deliberately
 * the same one — a store called {@code house} is one folder, whatever happens to be kept in it.
 * <b>Open folder</b> shows it; the {@code -wal} and {@code -shm} files next to the database are
 * SQLite's working files and are not to be deleted while HouseGraph is running.
 * <p>
 * <b>It is a real SQLite file.</b> Anything that opens SQLite can read it, back it up or repair it,
 * and a column added from outside is picked up here without ceremony. That was most of the reason
 * for choosing SQLite: the recovery story for someone's six months of sensor readings should not
 * depend on this library still existing.
 * <p>
 * <b>Nothing here schedules anything.</b> This node owns a connection and hands it out, which is the
 * one case the control-versus-action rule allows a node to own a lifecycle. When rows get written is
 * a trigger's business, wired into <b>Insert Row</b>.
 */
@Display.Name("Database")
@Display.Description("A local SQLite database, kept under this name, for nodes that store many records.")
@Node.Kind(NodeKind.RESOURCE)
@Node.Keywords({"database", "sqlite", "sql", "table", "rows", "records", "store", "persist", "local", "data"})
@Node.Type("database.DatabaseNode")
public class DatabaseNode extends BaseNode implements NodeContentProvider {

    private static final Logger log = Log.get(DatabaseNode.class);
    private static final String DEFAULT_NAME = "house";

    private final NodeVariable<Database> databaseOutput =
            new NodeVariable<>("Database", Database.class).transientValue();

    private String name = DEFAULT_NAME;

    private TextField nameField;
    private Label statusLabel;

    @Override
    public void process(ProcessContext ctx) {
        databaseOutput.setValue(databaseFor(name));
        refreshStatus();
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
        addOutput(databaseOutput);
    }

    /** The name, and only the name — never the schema. See the library's design note for why. */
    @Override
    public Map<String, String> saveState() {
        return Map.of("name", name);
    }

    @Override
    public void loadState(Map<String, String> state) {
        String saved = state.get("name");
        if (saved != null && !saved.isBlank()) {
            name = saved.trim();
        }
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        nameField = new TextField(name);
        nameField.setPromptText("Database name");
        // Committed on Enter or focus-out rather than per keystroke, so typing a name doesn't open
        // (and create a folder for) every intermediate value along the way.
        nameField.setOnAction(e -> commitName());
        nameField.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
            if (!isFocused) {
                commitName();
            }
        });

        Button openFolderButton = new Button("Open folder");
        openFolderButton.setMaxWidth(Double.MAX_VALUE);
        openFolderButton.setOnAction(e -> openStorageFolder());

        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");
        refreshStatus();

        return new VBox(4, nameField, openFolderButton, statusLabel);
    }

    private void commitName() {
        String typed = nameField.getText();
        String next = (typed == null || typed.isBlank()) ? DEFAULT_NAME : typed.trim();
        if (next.equals(name)) {
            return;
        }
        name = next;
        nameField.setText(name); // reflect blank -> default
        refreshStatus();
    }

    /** Opens the database's folder in the OS file manager, off the UI thread — it can block briefly. */
    private void openStorageFolder() {
        Path directory = AppDirectories.get().dataStore(name);
        Thread thread = new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(directory.toFile());
                } else {
                    log.warn("Opening a folder isn't supported on this platform: {}", directory);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Could not open database folder {}: {}", directory, e.getMessage());
            }
        }, "open-database-folder");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Updates the summary under the name field. Opening the file and counting rows is disk work, so
     * it happens on a worker and hops back to the FX thread with the answer — the rule the node
     * library docs state for anything a node starts itself.
     */
    private void refreshStatus() {
        if (statusLabel == null) {
            return;
        }
        String forName = name;
        Thread thread = new Thread(() -> {
            String text;
            try {
                text = summarise(databaseFor(forName));
            } catch (RuntimeException e) {
                text = "could not be opened";
                log.warn("Could not summarise database \"{}\": {}", forName, e.getMessage());
            }
            String summary = text;
            Platform.runLater(() -> {
                // The name may have moved on while this ran; don't paint a stale summary over it.
                if (statusLabel != null && forName.equals(name)) {
                    statusLabel.setText(summary);
                }
            });
        }, "summarise-database");
        thread.setDaemon(true);
        thread.start();
    }

    private static String summarise(Database database) {
        List<String> tables = database.tables();
        if (tables.isEmpty()) {
            return "empty - no tables yet";
        }
        long rows = 0;
        for (String table : tables) {
            rows += database.rowCount(table);
        }
        return tables.size() + (tables.size() == 1 ? " table, " : " tables, ") + rows
                + (rows == 1 ? " row" : " rows");
    }

    /** The shared database for {@code databaseName}, backed by {@code data-stores/<name>/database.db}. */
    private static Database databaseFor(String databaseName) {
        return Databases.forFile(AppDirectories.get().dataStore(databaseName).resolve("database.db"));
    }
}
