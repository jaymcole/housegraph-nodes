package io.github.jaymcole.housegraph.plugins.discord.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.Edge;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.logging.Log;
import io.github.jaymcole.housegraph.logging.Logger;
import io.github.jaymcole.housegraph.plugins.discord.ChoiceMode;
import io.github.jaymcole.housegraph.plugins.discord.CommandOption;
import io.github.jaymcole.housegraph.plugins.discord.DiscordBot;
import io.github.jaymcole.housegraph.plugins.discord.DiscordOptionType;
import io.github.jaymcole.housegraph.plugins.discord.DiscordReply;
import io.github.jaymcole.housegraph.plugins.discord.DiscordSlashCommand;
import io.github.jaymcole.housegraph.plugins.discord.SlashCommandRegistry;
import io.github.jaymcole.housegraph.plugins.discord.SlashCommandSpec;
import io.github.jaymcole.housegraph.resource.Subscription;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A modular Discord slash command with typed options. Declare a {@code /command} and its
 * options ({@code env}, {@code count:integer}); the node grows one named output port per option,
 * plus {@code Channel}, sender, and a {@code Reply} handle. When someone runs the command,
 * it fires its flow-out with each option's value on its matching port.
 * <p>
 * An option can also carry a list of values, which Discord treats one of two ways (see
 * {@link ChoiceMode}): <b>Only these</b> registers them as the option's choices, so Discord shows
 * a picker and refuses anything else — capped at 25 by Discord, and fixed until the command is
 * re-registered; <b>Suggest</b> turns on autocomplete instead, so the matching values are offered
 * as someone types but they may still submit something else, and the list isn't capped. Suggestions
 * are answered by the bot's session from what was registered, not by running the graph — Discord
 * gives about three seconds. Discord takes a list only on text and integer options, so the fields
 * are disabled for the others.
 * <p>
 * Wire a Discord Bot node's {@code Bot} output into this node's {@code Bot} input;
 * {@link #onInputEdgeAdded}/{@link #onInputEdgeRemoved} (re)subscribe against whatever
 * {@link DiscordBot} is currently on the other end, resolved through
 * {@link DiscordBotNode#botFrom(Edge)} rather than from the wired output's value, which on a
 * graph load is null (see there). Changing the options
 * {@link #rebuildPorts() rebuilds this node's ports} (edges to surviving options reconnect
 * by name). The command is <em>declared</em> into {@link SlashCommandRegistry} against the
 * wired bot instance and registered when that bot connects — so wire the bot and set up
 * commands, then connect; a change afterward (options, ephemeral, hidden, name) needs a
 * reconnect. Option and command names are lowercased to satisfy Discord.
 * <p>
 * "Hide from @everyone by default" registers the command with its default member
 * permissions disabled, so nobody sees it in their command picker until a server admin
 * grants it back to specific roles — a manual, per-server step in Discord's own Server
 * Settings → Integrations page. There's no bot-token API left for setting per-role
 * command visibility (see {@link DiscordBot#syncCommands}), so this node can't do that
 * part for you.
 */
@Display.Name("Discord Slash Command")
@Node.Type("discord.DiscordSlashCommandNode")
public class DiscordSlashCommandNode extends BaseNode implements NodeContentProvider {

    private static final Logger log = Log.get(DiscordSlashCommandNode.class);

    private static final String DESCRIPTION = "HouseGraph command";

    /**
     * Where the full option list is saved, and where the one a build too old to know about it
     * reads from.
     * <p>
     * Both are written on every save, because they are read by different versions of this
     * library and a graph moves between them. {@code options} is the older key and holds the
     * older, comma-separated {@code name:type} text; {@code optionsJson} holds everything an
     * option can carry today. Writing only the JSON — which is what this node did when the JSON
     * format arrived — put a value into {@code options} that an older build split on commas,
     * turning <code>[{"name":"prompt",…}]</code> into an option literally named
     * <code>[{"name"</code>. Discord rejects that name, and rejecting it cost the whole command:
     * every slash command in such a graph vanished from Discord until the library was updated.
     * So the old key keeps holding something an old build reads correctly — names and types;
     * values and their mode are newer than that format and simply don't appear there.
     */
    private static final String OPTIONS_KEY = "optionsJson";

    private static final String LEGACY_OPTIONS_KEY = "options";

    private final NodeVariable<DiscordBot> botInput = new NodeVariable<>("Bot", DiscordBot.class).transientValue().required();
    private final NodeVariable<String> channel = new NodeVariable<>("Channel", String.class);
    private final NodeVariable<String> senderId = new NodeVariable<>("Sender ID", String.class);
    private final NodeVariable<String> senderName = new NodeVariable<>("Sender Name", String.class);
    private final NodeVariable<DiscordReply> reply = new NodeVariable<>("Reply", DiscordReply.class).transientValue();
    private final Map<String, NodeVariable<String>> optionOutputs = new LinkedHashMap<>();
    private final List<CommandOption> options = new ArrayList<>();
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private DiscordBot bot;
    private String command = "command";
    private boolean ephemeral;
    private boolean hiddenByDefault;
    private DiscordBot declaredBot;
    private String declaredCommand;
    private Subscription subscription;

    @Override
    public void process(ProcessContext ctx) {
        // Outputs are set from the incoming invocation just before execute(); nothing to compute.
    }

    @Override
    public void configureInputs() {
        addInput(botInput);
    }

    @Override
    public void configureOutputs() {
        // One output per declared option (rebuilt whenever the options change), then the
        // fixed metadata outputs.
        optionOutputs.clear();
        for (CommandOption option : options) {
            NodeVariable<String> output = new NodeVariable<>(option.name(), String.class);
            optionOutputs.put(option.name(), output);
            addOutput(output);
        }
        addOutput(channel);
        addOutput(senderId);
        addOutput(senderName);
        addOutput(reply);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        state.put("command", command);
        state.put("ephemeral", Boolean.toString(ephemeral));
        state.put("hiddenByDefault", Boolean.toString(hiddenByDefault));
        state.put(OPTIONS_KEY, formatOptions(options));
        state.put(LEGACY_OPTIONS_KEY, formatLegacyOptions(options));
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        String saved = state.get("command");
        if (saved != null && !saved.isBlank()) {
            command = saved;
        }
        ephemeral = Boolean.parseBoolean(state.get("ephemeral"));
        hiddenByDefault = Boolean.parseBoolean(state.get("hiddenByDefault"));
        options.clear();
        String json = state.get(OPTIONS_KEY);
        // The JSON key is the authority; the legacy key is what a build older than it reads, and
        // what a graph saved by one still carries here.
        options.addAll(parseOptions(normalizedCommand(), json == null || json.isBlank()
                ? state.get(LEGACY_OPTIONS_KEY)
                : json));
    }

    @Override
    protected void onInputEdgeAdded(Edge edge) {
        if (edge.getTargetVariable() == botInput) {
            subscribeTo(DiscordBotNode.botFrom(edge));
        }
    }

    @Override
    protected void onInputEdgeRemoved(Edge edge) {
        if (edge.getTargetVariable() == botInput) {
            subscribeTo(null);
        }
    }

    @Override
    protected void onRemoved() {
        subscribeTo(null);
    }

    private void subscribeTo(DiscordBot newBot) {
        if (subscription != null) {
            subscription.cancel();
            subscription = null;
        }
        bot = newBot;
        botInput.setValue(newBot);
        redeclare();
        if (bot != null) {
            subscription = bot.addSlashListener(this::onCommand);
        }
    }

    /** Withdraws any previous declaration and declares the current command + options (if a bot and name are set). */
    private void redeclare() {
        if (declaredBot != null && declaredCommand != null) {
            SlashCommandRegistry.shared().withdraw(declaredBot, declaredCommand);
        }
        String name = normalizedCommand();
        if (bot != null && name != null) {
            declaredBot = bot;
            declaredCommand = name;
            SlashCommandRegistry.shared().declare(declaredBot,
                    new SlashCommandSpec(name, DESCRIPTION, ephemeral, hiddenByDefault, new ArrayList<>(options)));
        } else {
            declaredBot = null;
            declaredCommand = null;
        }
    }

    private void onCommand(DiscordSlashCommand slash) {
        String name = normalizedCommand();
        if (name == null || !name.equals(slash.command())) {
            return;
        }
        try {
            execute(() -> {
                for (Map.Entry<String, NodeVariable<String>> option : optionOutputs.entrySet()) {
                    option.getValue().setValue(slash.options().get(option.getKey()));
                }
                channel.setValue(slash.channelId());
                senderId.setValue(slash.authorId());
                senderName.setValue(slash.authorName());
                reply.setValue(slash.reply());
            });
        } catch (IllegalStateException e) {
            // Removed just as the invocation arrived; ignore.
        }
    }

    private String normalizedCommand() {
        if (command == null || command.isBlank()) {
            return null;
        }
        return command.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        TextField commandField = new TextField(command);
        commandField.setPromptText("command (no slash)");
        commandField.textProperty().addListener((obs, old, value) -> {
            command = value;
            redeclare();
        });

        CheckBox ephemeralBox = new CheckBox("Ephemeral reply");
        ephemeralBox.setStyle("-fx-text-fill: #dddddd; -fx-font-size: 11px;");
        ephemeralBox.setSelected(ephemeral);
        ephemeralBox.selectedProperty().addListener((obs, was, now) -> {
            ephemeral = now;
            redeclare();
        });

        CheckBox hiddenBox = new CheckBox("Hide from @everyone by default");
        hiddenBox.setStyle("-fx-text-fill: #dddddd; -fx-font-size: 11px;");
        hiddenBox.setSelected(hiddenByDefault);
        hiddenBox.selectedProperty().addListener((obs, was, now) -> {
            hiddenByDefault = now;
            redeclare();
        });
        Label hiddenHint = new Label("After reconnecting, grant it to roles per-server in Discord's\nServer Settings → Integrations — that part can't be automated.");
        hiddenHint.setWrapText(true);
        hiddenHint.setStyle("-fx-text-fill: #888888; -fx-font-size: 9px;");

        // Options are edited as rows and applied all at once, so the node's ports rebuild
        // just once (on Apply) rather than jarringly on every keystroke.
        VBox optionRows = new VBox(6);
        for (CommandOption option : options) {
            optionRows.getChildren().add(new OptionRow(option));
        }

        Button addButton = new Button("+ Option");
        addButton.setOnAction(e -> optionRows.getChildren()
                .add(new OptionRow(new CommandOption("option" + (optionRows.getChildren().size() + 1), DiscordOptionType.TEXT))));

        Button applyButton = new Button("Apply");
        applyButton.setOnAction(e -> applyOptionRows(optionRows));

        Label optionsLabel = new Label("Options");
        optionsLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 10px;");
        Label valuesHint = new Label("Leave an option's values empty for free input. \"Only these\" makes Discord\nrefuse anything else (max 25); \"Suggest\" autocompletes but still allows others.");
        valuesHint.setWrapText(true);
        valuesHint.setStyle("-fx-text-fill: #888888; -fx-font-size: 9px;");

        return new VBox(4, commandField, ephemeralBox, hiddenBox, hiddenHint,
                optionsLabel, valuesHint, optionRows, new HBox(6, addButton, applyButton));
    }

    /**
     * One editable option: name and type on the first line, the values it offers on the second.
     * A class rather than an HBox read back by index, because the controls now depend on each
     * other — an empty value list means free input whatever the mode says, and a type Discord
     * takes no list for disables both. Read back on Apply.
     */
    private static final class OptionRow extends VBox {

        private final TextField name = new TextField();
        private final ComboBox<DiscordOptionType> type = new ComboBox<>();
        private final ComboBox<ChoiceMode> mode = new ComboBox<>();
        private final TextField values = new TextField();

        OptionRow(CommandOption option) {
            super(3);
            name.setText(option.name());
            name.setPromptText("name");
            HBox.setHgrow(name, Priority.ALWAYS);

            type.getItems().setAll(DiscordOptionType.values());
            type.setValue(option.type());
            type.valueProperty().addListener((obs, was, now) -> updateValueControls());

            // FREE isn't offered: it is what an empty value list already means, and a mode
            // saying "no list" next to a field holding one is a contradiction to explain away.
            mode.getItems().setAll(ChoiceMode.RESTRICTED, ChoiceMode.SUGGESTED);
            mode.setValue(option.choiceMode() == ChoiceMode.SUGGESTED ? ChoiceMode.SUGGESTED : ChoiceMode.RESTRICTED);
            mode.setConverter(new StringConverter<>() {
                @Override
                public String toString(ChoiceMode value) {
                    return value == ChoiceMode.SUGGESTED ? "Suggest" : "Only these";
                }

                @Override
                public ChoiceMode fromString(String text) {
                    return "Suggest".equals(text) ? ChoiceMode.SUGGESTED : ChoiceMode.RESTRICTED;
                }
            });

            values.setText(String.join(", ", option.choices()));
            values.setPromptText("values, comma separated");
            HBox.setHgrow(values, Priority.ALWAYS);

            Button remove = new Button("×");
            remove.setOnAction(e -> ((VBox) getParent()).getChildren().remove(this));

            getChildren().addAll(new HBox(4, name, type, remove), new HBox(4, mode, values));
            updateValueControls();
        }

        /** What this row describes now, or null if it has no usable name. */
        CommandOption toOption() {
            String normalized = name.getText() == null ? "" : name.getText().trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                return null;
            }
            if (!CommandOption.isValidName(normalized)) {
                // Dropped here rather than at registration: Discord refuses the name, and until
                // the gateway stopped taking the command down with it that refusal cost every
                // other option too.
                log.warn("Ignoring slash command option \"{}\": Discord takes only letters, digits,"
                        + " \"-\" and \"_\" in an option name", normalized);
                return null;
            }
            List<String> declared = splitValues(values.getText());
            // CommandOption normalizes the rest: an empty list pairs with FREE, and a type
            // Discord takes no list for drops one it was handed anyway.
            return new CommandOption(normalized,
                    type.getValue() == null ? DiscordOptionType.TEXT : type.getValue(),
                    declared,
                    declared.isEmpty() ? ChoiceMode.FREE : mode.getValue());
        }

        /** Greys out the value fields for a type Discord takes no list for (boolean, user). */
        private void updateValueControls() {
            boolean supported = type.getValue() == null || type.getValue().supportsChoices();
            mode.setDisable(!supported);
            values.setDisable(!supported);
        }

        private static List<String> splitValues(String text) {
            List<String> values = new ArrayList<>();
            if (text == null) {
                return values;
            }
            for (String entry : text.split(",")) {
                if (!entry.isBlank()) {
                    values.add(entry.trim());
                }
            }
            return values;
        }
    }

    private void applyOptionRows(VBox optionRows) {
        List<CommandOption> edited = new ArrayList<>();
        for (javafx.scene.Node rowNode : optionRows.getChildren()) {
            CommandOption option = ((OptionRow) rowNode).toOption();
            if (option != null) {
                edited.add(option);
            }
        }
        if (edited.equals(options)) {
            return; // no change - avoid a needless rebuild
        }
        options.clear();
        options.addAll(edited);
        redeclare();
        rebuildPorts();
    }

    // --- Option state -------------------------------------------------------------

    /**
     * The options as saved under {@link #OPTIONS_KEY}: a JSON array of
     * <code>{name, type, values, mode}</code> entries, the last two present only for an option
     * that offers values. JSON rather than the {@code "env, count:integer"} text this used to
     * save, because those values are arbitrary text a person typed and every delimiter that could
     * separate them is one a value is allowed to contain. Graphs saved in the old format still
     * load — see {@link #parseOptions}.
     */
    private static String formatOptions(List<CommandOption> options) {
        JSONArray saved = new JSONArray();
        for (CommandOption option : options) {
            JSONObject entry = new JSONObject();
            entry.put("name", option.name());
            entry.put("type", option.type().name().toLowerCase(Locale.ROOT));
            if (!option.choices().isEmpty()) {
                entry.put("values", new JSONArray(option.choices()));
                entry.put("mode", option.choiceMode().name().toLowerCase(Locale.ROOT));
            }
            saved.put(entry);
        }
        return saved.toString();
    }

    /**
     * The same options in the pre-JSON {@code "env, count:integer"} text, saved alongside the
     * JSON under {@link #LEGACY_OPTIONS_KEY} so a build older than the JSON format reads names
     * and types rather than shredding the JSON on its commas (see {@link #OPTIONS_KEY}). The type
     * is always written out: this text is only ever read by the parser below, which defaults an
     * unrecognized one to text anyway, and being explicit costs nothing. Names are safe to join
     * on a comma because {@link CommandOption#isValidName} is what let them in.
     */
    private static String formatLegacyOptions(List<CommandOption> options) {
        List<String> entries = new ArrayList<>();
        for (CommandOption option : options) {
            entries.add(option.name() + ":" + option.type().name().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", entries);
    }

    /**
     * Reads back what {@link #formatOptions} wrote — or, for a graph saved before options could
     * offer values, the {@code "env, count:integer"} text that came before it. Unreadable state
     * loads as no options rather than throwing, which would take the whole graph down with it,
     * and so does a name Discord would refuse: {@code command} is named in the warning so the
     * node it came from can be found on the graph.
     */
    private static List<CommandOption> parseOptions(String command, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (!text.trim().startsWith("[")) {
            return parseLegacyOptions(command, text);
        }
        List<CommandOption> parsed = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(text);
            for (int i = 0; i < saved.length(); i++) {
                JSONObject entry = saved.getJSONObject(i);
                String name = entry.optString("name").trim().toLowerCase(Locale.ROOT);
                if (name.isEmpty() || !usableName(command, name)) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                JSONArray declared = entry.optJSONArray("values");
                for (int value = 0; declared != null && value < declared.length(); value++) {
                    values.add(declared.optString(value));
                }
                parsed.add(new CommandOption(name, parseType(entry.optString("type")), values,
                        values.isEmpty() ? ChoiceMode.FREE : parseMode(entry.optString("mode"))));
            }
        } catch (RuntimeException e) {
            log.warn("Could not read this slash command node's saved options; starting with none: {}", e.getMessage());
            return List.of();
        }
        return parsed;
    }

    /** The pre-values format: a comma-separated {@code name[:type]} list, all of it free input. */
    private static List<CommandOption> parseLegacyOptions(String command, String text) {
        List<CommandOption> parsed = new ArrayList<>();
        for (String entry : text.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            String name = (colon < 0 ? trimmed : trimmed.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
            DiscordOptionType type = colon < 0 ? DiscordOptionType.TEXT : parseType(trimmed.substring(colon + 1).trim());
            if (!name.isEmpty() && usableName(command, name)) {
                parsed.add(new CommandOption(name, type));
            }
        }
        return parsed;
    }

    /**
     * Whether {@code name} is one Discord would take, warning and answering false if not. Saved
     * state is checked here rather than at registration because a name this far out of shape got
     * there by a format being misread — an older build splitting today's JSON on its commas is
     * the case that has actually happened — and dropping it here leaves the rest of the command
     * standing.
     */
    private static boolean usableName(String command, String name) {
        if (CommandOption.isValidName(name)) {
            return true;
        }
        log.warn("/{}: dropping saved option \"{}\" - Discord takes only letters, digits, \"-\" and"
                + " \"_\" in an option name, so re-add this option on the node", command, name);
        return false;
    }

    private static DiscordOptionType parseType(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "integer", "int", "number" -> DiscordOptionType.INTEGER;
            case "boolean", "bool" -> DiscordOptionType.BOOLEAN;
            case "user" -> DiscordOptionType.USER;
            default -> DiscordOptionType.TEXT;
        };
    }

    /**
     * Only called with a value list present, so an unreadable (or hand-written and missing) mode
     * means restricting rather than dropping the list: a list the user meant to enforce and that
     * quietly stopped being enforced is the worse of the two failures.
     */
    private static ChoiceMode parseMode(String text) {
        return "suggested".equals(text.toLowerCase(Locale.ROOT)) ? ChoiceMode.SUGGESTED : ChoiceMode.RESTRICTED;
    }
}
