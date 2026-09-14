package io.github.jaymcole.housegraph.plugins.github.nodes;

import io.github.jaymcole.housegraph.annotations.Display;
import io.github.jaymcole.housegraph.annotations.Node;
import io.github.jaymcole.housegraph.graph.BaseNode;
import io.github.jaymcole.housegraph.graph.FlowPort;
import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import io.github.jaymcole.housegraph.plugins.github.GitRepoSync;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Checks a git repository for new commits and pulls them into a local folder — cloning it first
 * if it's empty (see {@link GitRepoSync}: a hard reset onto the remote's tracking branch, not a
 * merge, since this is meant for an unattended checkout something else runs off of).
 * <p>
 * Purely flow-driven — it has no timer of its own. Wire something that fires repeatedly (e.g.
 * the host app's Repeating Trigger) into its flow-in to poll on an interval. <b>Checked</b> fires
 * on every run that reached the remote, whether or not anything changed; <b>Pulled</b> fires only
 * when a new commit actually landed, so a rebuild/restart/notify step chained off it doesn't re-run
 * on every no-op poll. The <b>Commit</b> output is set either way, to the commit the folder now
 * points at.
 * <p>
 * A sync that <em>fails</em> — no network, bad credentials, a path that isn't writable — fires
 * neither, and fires the engine-owned <b>Error</b> port instead. Wire that to be told about a
 * checkout that has silently stopped updating; leave it unwired and the branch simply stops, which
 * is the right default for a poll that will try again on the next tick.
 */
@Display.Name("Git Sync")
@Node.Type("github.GitSyncNode")
public class GitSyncNode extends BaseNode {

    private final NodeVariable<String> repositoryUrl = new NodeVariable<>("Repository URL", String.class, true).required();
    private final NodeVariable<String> localPath = new NodeVariable<>("Local Path", String.class, true).required();

    private final NodeVariable<String> commitId = new NodeVariable<>("Commit", String.class);

    private final FlowPort in = new FlowPort("", FlowPort.Direction.IN);
    private final FlowPort checked = new FlowPort("Checked", FlowPort.Direction.OUT);
    private final FlowPort pulled = new FlowPort("Pulled", FlowPort.Direction.OUT);

    @Override
    public void process(ProcessContext ctx) {
        // Ordering is readability, not defence. A throw below routes this firing to the engine's
        // Error port and discards every activation made before it (FailurePolicy.HALT), so an
        // already-activated Checked cannot make a failed sync look like a successful one. This call
        // used to sit here specifically to defend against the "activated nothing -> fire everything"
        // default; that is now the engine's job.
        activate(checked);

        String url = repositoryUrl.getValue();
        String path = localPath.getValue();
        GitRepoSync.Result result;
        try {
            result = GitRepoSync.sync(url, Path.of(path));
        } catch (IOException | GitAPIException e) {
            throw new RuntimeException("Git sync failed for " + url, e);
        }

        commitId.setValue(result.commitId());
        if (result.changed()) {
            activate(pulled);
        }
    }

    @Override
    public void configureInputs() {
        addInput(repositoryUrl);
        addInput(localPath);
    }

    @Override
    public void configureOutputs() {
        addOutput(commitId);
    }

    @Override
    public void configureFlowInputs() {
        addFlowInput(in);
    }

    @Override
    public void configureFlowOutputs() {
        addFlowOutput(checked);
        addFlowOutput(pulled);
    }
}
