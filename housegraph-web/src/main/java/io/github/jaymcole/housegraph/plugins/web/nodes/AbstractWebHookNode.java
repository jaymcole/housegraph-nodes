package io.github.jaymcole.housegraph.plugins.web.nodes;

import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.web.RouteRegistry;
import io.github.jaymcole.housegraph.plugins.web.WebHookEvent;
import io.github.jaymcole.housegraph.plugins.web.WebHookRoute;
import io.github.jaymcole.housegraph.resource.ResourceRegistry;
import io.github.jaymcole.housegraph.resource.Subscription;
import io.github.jaymcole.housegraph.sdk.NodeContentProvider;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Shared plumbing for a node that fires when a declared {@code /hooks/...} route on a named Web
 * Server is requested: {@link io.github.jaymcole.housegraph.plugins.web.nodes.WebHookNode}
 * (fire-and-forget) and {@link WebHookRequestNode} (holds the response for a reply).
 * <p>
 * Same shape as the Discord library's {@code DiscordSlashCommandNode}: the node <em>declares</em>
 * its route into {@link RouteRegistry} — so {@code LocalWebServer}'s dispatcher knows the route
 * exists and whether to hold the response — and separately {@code subscribe}s to the server's
 * published {@link WebHookEvent}s, filtering to the one path/method it owns. Declaring and
 * subscribing both happen from {@link #onActivated()}/{@link #onRemoved()}; there's no
 * Start/Stop, since listening doesn't itself open a connection.
 */
public abstract class AbstractWebHookNode extends BaseNode implements NodeContentProvider {

    private static final String DEFAULT_PATH = "/hook";
    private static final String DEFAULT_METHOD = "POST";

    protected final NodeVariable<String> method = new NodeVariable<>("Method", String.class);
    protected final NodeVariable<String> path = new NodeVariable<>("Path", String.class);
    @SuppressWarnings("unchecked")
    protected final NodeVariable<Map<String, String>> headers =
            new NodeVariable<>("Headers", (Class<Map<String, String>>) (Class<?>) Map.class);
    @SuppressWarnings("unchecked")
    protected final NodeVariable<Map<String, String>> query =
            new NodeVariable<>("Query", (Class<Map<String, String>>) (Class<?>) Map.class);
    protected final NodeVariable<String> body = new NodeVariable<>("Body", String.class);
    private final FlowPort out = new FlowPort("", FlowPort.Direction.OUT);

    private String resourceName;
    private String routePath = DEFAULT_PATH;
    private String httpMethod = DEFAULT_METHOD;
    private String declaredServer;
    private String declaredPath;
    private String declaredMethod;
    private Subscription subscription;

    private ComboBox<String> serverChooser;
    private TextField pathField;
    private ComboBox<String> methodChooser;

    @Override
    public void process(ProcessContext ctx) {
        // Outputs are set from the incoming request just before execute(); nothing to compute.
    }

    @Override
    public void configureInputs() {
    }

    @Override
    public void configureOutputs() {
        addOutput(method);
        addOutput(path);
        addOutput(headers);
        addOutput(query);
        addOutput(body);
        configureExtraOutputs();
    }

    /** Hook for a subclass to add outputs beyond the common request shape (e.g. a Reply handle). */
    protected void configureExtraOutputs() {
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(out);
    }

    /** Whether this node's route holds the HTTP response open for a reply. */
    protected abstract boolean awaitsReply();

    /** How long a held route waits before answering 504. Meaningless when {@link #awaitsReply()} is false. */
    protected int replyTimeoutSeconds() {
        return 0;
    }

    /** Hook for a subclass to capture anything beyond the common outputs (e.g. the reply handle). */
    protected void onMatched(WebHookEvent event) {
    }

    /** Hook for a subclass to add UI beyond the server/path/method chooser (e.g. a timeout field). */
    protected VBox createExtraContent() {
        return null;
    }

    protected void saveExtraState(Map<String, String> state) {
    }

    protected void loadExtraState(Map<String, String> state) {
    }

    @Override
    public Map<String, String> saveState() {
        Map<String, String> state = new HashMap<>();
        state.put("path", routePath);
        state.put("method", httpMethod);
        if (resourceName != null) {
            state.put("resource", resourceName);
        }
        saveExtraState(state);
        return state;
    }

    @Override
    public void loadState(Map<String, String> state) {
        String savedPath = state.get("path");
        if (savedPath != null && !savedPath.isBlank()) {
            routePath = savedPath;
        }
        String savedMethod = state.get("method");
        if (savedMethod != null && !savedMethod.isBlank()) {
            httpMethod = savedMethod;
        }
        resourceName = emptyToNull(state.get("resource"));
        loadExtraState(state);
    }

    @Override
    protected void onActivated() {
        subscribeTo(resourceName);
    }

    @Override
    protected void onRemoved() {
        subscribeTo(null);
    }

    private void subscribeTo(String name) {
        if (subscription != null) {
            subscription.cancel();
            subscription = null;
        }
        resourceName = name;
        redeclare();
        if (name != null) {
            subscription = ResourceRegistry.shared().subscribe(name, payload -> {
                if (payload instanceof WebHookEvent event) {
                    onEvent(event);
                }
            });
        }
    }

    /** Withdraws any previous declaration and declares the current path/method, if a server and path are set. */
    protected void redeclare() {
        if (declaredServer != null && declaredPath != null) {
            RouteRegistry.shared().withdraw(declaredServer, declaredMethod, declaredPath);
        }
        String normalizedPath = normalizedPath();
        if (resourceName != null && normalizedPath != null) {
            declaredServer = resourceName;
            declaredPath = normalizedPath;
            declaredMethod = normalizedMethod();
            RouteRegistry.shared().declare(declaredServer,
                    new WebHookRoute(declaredPath, declaredMethod, awaitsReply(), replyTimeoutSeconds()));
        } else {
            declaredServer = null;
            declaredPath = null;
            declaredMethod = null;
        }
    }

    private void onEvent(WebHookEvent event) {
        String normalizedPath = normalizedPath();
        if (normalizedPath == null || !normalizedPath.equals(event.path()) || !normalizedMethod().equals(event.method())) {
            return;
        }
        try {
            execute(() -> {
                method.setValue(event.method());
                path.setValue(event.path());
                headers.setValue(event.headers());
                query.setValue(event.query());
                body.setValue(event.body());
                onMatched(event);
            });
        } catch (IllegalStateException e) {
            // Removed just as the request arrived; ignore.
        }
    }

    private String normalizedPath() {
        if (routePath == null || routePath.isBlank()) {
            return null;
        }
        String trimmed = routePath.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private String normalizedMethod() {
        String raw = (httpMethod == null || httpMethod.isBlank()) ? DEFAULT_METHOD : httpMethod;
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    @Override
    public javafx.scene.Node createNodeContent() {
        serverChooser = new ComboBox<>();
        serverChooser.setPromptText("On server…");
        serverChooser.setMaxWidth(Double.MAX_VALUE);
        serverChooser.getItems().setAll(ResourceRegistry.shared().activeNames());
        if (resourceName != null) {
            serverChooser.setValue(resourceName);
        }
        serverChooser.setOnShowing(e -> serverChooser.getItems().setAll(ResourceRegistry.shared().activeNames()));
        serverChooser.setOnAction(e -> subscribeTo(serverChooser.getValue()));

        pathField = new TextField(routePath);
        pathField.setPromptText("/path (served at /hooks/path)");
        pathField.textProperty().addListener((obs, old, value) -> {
            routePath = value;
            redeclare();
        });

        methodChooser = new ComboBox<>();
        methodChooser.getItems().setAll("GET", "POST", "PUT", "PATCH", "DELETE");
        methodChooser.setValue(normalizedMethod());
        methodChooser.setOnAction(e -> {
            httpMethod = methodChooser.getValue();
            redeclare();
        });

        VBox box = new VBox(4, serverChooser, pathField, methodChooser);
        VBox extra = createExtraContent();
        if (extra != null) {
            box.getChildren().add(extra);
        }
        return box;
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
