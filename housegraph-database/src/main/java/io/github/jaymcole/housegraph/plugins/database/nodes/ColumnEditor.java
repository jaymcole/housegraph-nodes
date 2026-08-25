package io.github.jaymcole.housegraph.plugins.database.nodes;

import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugins.database.Database;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The window behind the <b>Database</b> node's <b>Columns…</b> button: what tables exist, what
 * columns they have, and the two changes this library will make to them.
 *
 * <h2>Why this is a window and not a node</h2>
 *
 * Renaming and dropping a column are the schema changes that destroy data, and they are reached by a
 * person clicking, once, having been shown what it costs. The alternative — a node that reconciles a
 * declared schema when it runs — re-applies itself on every firing, so one typo'd column name is
 * dropped again and again on a timer with nobody watching. Adding a column needs none of this and
 * happens by itself (see {@link Database#insert}); it is only the destructive direction that is
 * deliberately awkward.
 *
 * <h2>What it shows before it does anything</h2>
 *
 * Every column is listed with how many rows actually have a value in it, so the question is "drop
 * {@code notes}? 4,812 of 5,001 rows have a value" rather than "are you sure?". The database is
 * copied before either change (see {@link Database#backup()}) and the window says where the copy
 * went — which turns "I renamed the wrong thing and lost six months of history" into a rename in a
 * file manager.
 *
 * <h2>Threading</h2>
 *
 * Every database call here is disk work and runs on a worker; only the result touches the scene. The
 * window is otherwise ordinary JavaFX built on the FX thread, where a node's UI callbacks already
 * arrive.
 */
final class ColumnEditor {

    private static final Logger log = Log.get(ColumnEditor.class);

    /** One row of the column list: the name, and how much would be lost with it. */
    private record ColumnSummary(String column, int values, boolean structural) {

        String describe() {
            if (structural) {
                return column + "   (kept: this is how the table works)";
            }
            return column + "   " + values + (values == 1 ? " row has a value" : " rows have a value");
        }
    }

    private final Database database;
    private final String databaseName;
    private final Runnable onChanged;

    private final ComboBox<String> tables = new ComboBox<>();
    private final ListView<ColumnSummary> columns = new ListView<>();
    private final Label status = new Label();
    private final Label history = new Label();
    private final Button rename = new Button("Rename…");
    private final Button drop = new Button("Drop…");

    ColumnEditor(Database database, String databaseName, Runnable onChanged) {
        this.database = database;
        this.databaseName = databaseName;
        this.onChanged = onChanged;
    }

    /** Builds and shows the window. Call on the FX thread. */
    void show() {
        Stage stage = new Stage();
        stage.setTitle("Columns - " + databaseName);

        tables.setMaxWidth(Double.MAX_VALUE);
        tables.valueProperty().addListener((observable, previous, table) -> refreshColumns());

        columns.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ColumnSummary summary, boolean empty) {
                super.updateItem(summary, empty);
                setText(empty || summary == null ? null : summary.describe());
            }
        });
        columns.getSelectionModel().selectedItemProperty()
                .addListener((observable, previous, selected) -> refreshButtons());
        VBox.setVgrow(columns, Priority.ALWAYS);

        rename.setOnAction(event -> renameSelected());
        drop.setOnAction(event -> dropSelected());
        refreshButtons();

        status.setWrapText(true);
        history.setWrapText(true);
        history.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");

        VBox root = new VBox(8,
                new Label("Table"), tables,
                new Label("Columns"), columns,
                new HBox(8, rename, drop),
                status, history);
        root.setPadding(new Insets(12));
        stage.setScene(new Scene(root, 460, 460));
        stage.show();

        refreshTables();
    }

    private void refreshTables() {
        offFxThread(database::tables, names -> {
            tables.setItems(FXCollections.observableArrayList(names));
            if (names.isEmpty()) {
                status.setText("This database has no tables yet. One appears the first time a row is inserted.");
                columns.setItems(FXCollections.observableArrayList());
                return;
            }
            String previous = tables.getValue();
            tables.setValue(names.contains(previous) ? previous : names.get(0));
            refreshColumns();
        });
    }

    private void refreshColumns() {
        String table = tables.getValue();
        if (table == null) {
            return;
        }
        offFxThread(() -> summarise(table), summaries -> {
            columns.setItems(FXCollections.observableArrayList(summaries));
            refreshButtons();
        });
        offFxThread(database::migrations, applied -> history.setText(applied.isEmpty()
                ? "No schema changes have been made here."
                : "Changes so far:\n" + String.join("\n", applied.subList(0, Math.min(5, applied.size())))));
    }

    /** Reads each column and what it holds. Runs on a worker; see the class documentation. */
    private List<ColumnSummary> summarise(String table) {
        return database.columns(table).stream()
                .map(column -> {
                    boolean structural = Database.ID_COLUMN.equals(column)
                            || Database.CREATED_AT_COLUMN.equals(column);
                    return new ColumnSummary(column, structural ? 0 : database.valuesIn(table, column), structural);
                })
                .toList();
    }

    private void refreshButtons() {
        ColumnSummary selected = columns.getSelectionModel().getSelectedItem();
        boolean changeable = selected != null && !selected.structural();
        rename.setDisable(!changeable);
        drop.setDisable(!changeable);
    }

    private void renameSelected() {
        ColumnSummary selected = columns.getSelectionModel().getSelectedItem();
        String table = tables.getValue();
        if (selected == null || table == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog(selected.column());
        dialog.setTitle("Rename column");
        dialog.setHeaderText("Rename \"" + selected.column() + "\" in \"" + table + "\"");
        dialog.setContentText("New name");
        dialog.showAndWait().ifPresent(name -> apply(
                () -> database.renameColumn(table, selected.column(), name.trim()),
                "Renamed \"" + selected.column() + "\" to \"" + name.trim() + "\"."));
    }

    private void dropSelected() {
        ColumnSummary selected = columns.getSelectionModel().getSelectedItem();
        String table = tables.getValue();
        if (selected == null || table == null) {
            return;
        }
        // The count is the whole point of this dialog: "are you sure?" is not a question anyone can
        // answer, and "4,812 of 5,001 rows have a value" is.
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Drop \"" + selected.column() + "\" from \"" + table + "\"?\n\n"
                        + selected.values() + (selected.values() == 1 ? " row has" : " rows have")
                        + " a value in it, and it will be gone.\n\n"
                        + "A copy of the database is written first, next to it.",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle("Drop column");
        confirm.setHeaderText(null);
        confirm.showAndWait()
                .filter(button -> button == ButtonType.OK)
                .ifPresent(button -> apply(
                        () -> database.dropColumn(table, selected.column()),
                        "Dropped \"" + selected.column() + "\"."));
    }

    /** Runs one schema change on a worker and reports where the pre-change copy went. */
    private void apply(Supplier<Path> change, String describe) {
        status.setText("Working…");
        Thread thread = new Thread(() -> {
            String message;
            try {
                Path copy = change.get();
                message = describe + " The database as it was is in " + copy.getFileName() + ".";
            } catch (RuntimeException e) {
                message = "That didn't happen: " + e.getMessage();
                log.warn("Schema change failed on database \"{}\": {}", databaseName, e.getMessage());
            }
            String result = message;
            Platform.runLater(() -> {
                status.setText(result);
                refreshTables();
                onChanged.run();
            });
        }, "housegraph-database-schema-change");
        thread.setDaemon(true);
        thread.start();
    }

    /** Reads from the database on a worker and hands the answer back on the FX thread. */
    private <T> void offFxThread(Supplier<T> read, Consumer<T> consume) {
        Thread thread = new Thread(() -> {
            T value;
            try {
                value = read.get();
            } catch (RuntimeException e) {
                log.warn("Could not read database \"{}\": {}", databaseName, e.getMessage());
                return;
            }
            Platform.runLater(() -> consume.accept(value));
        }, "housegraph-database-read");
        thread.setDaemon(true);
        thread.start();
    }
}
