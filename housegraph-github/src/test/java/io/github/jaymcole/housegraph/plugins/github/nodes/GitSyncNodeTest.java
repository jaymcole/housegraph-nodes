package io.github.jaymcole.housegraph.plugins.github.nodes;

import io.github.jaymcole.housegraph.graph.NodeVariable;
import io.github.jaymcole.housegraph.graph.ProcessContext;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link GitSyncNode} headlessly: {@code process()} invoked directly with
 * {@link ProcessContext#uncancelled()} (no {@code NodeGraph}) against a local file-based
 * "remote". Which flow-out port fires is graph-cascade behavior — {@code BaseNode.activate()} is
 * a no-op with no {@code ExecutionContext} bound — so that part, and the underlying sync
 * mechanics, are covered by {@code GitRepoSyncTest} instead; this stays focused on the node's
 * declared ports and its Commit output.
 */
class GitSyncNodeTest {

    @TempDir
    Path tempDir;

    private Path checkoutPath;
    private Git remote;

    @BeforeEach
    void setUp() throws Exception {
        Path remotePath = tempDir.resolve("remote");
        checkoutPath = tempDir.resolve("checkout");
        Files.createDirectories(remotePath);
        remote = Git.init().setDirectory(remotePath.toFile()).setInitialBranch("main").call();
        commitFile(remote, "README.md", "hello");
    }

    @AfterEach
    void tearDown() {
        remote.close();
    }

    @Test
    void declaresAFlowInAndTwoNamedFlowOuts() {
        GitSyncNode node = new GitSyncNode();

        assertEquals(1, node.getFlowInputs().size());
        List<String> outNames = node.getFlowOutputs().stream().map(port -> port.name).toList();
        assertEquals(List.of("Checked", "Pulled"), outNames);
    }

    @Test
    void firstCheckClonesAndSetsTheCommitOutput() throws Exception {
        GitSyncNode node = configuredNode();

        node.process(ProcessContext.uncancelled());

        assertNotNull(commitOutput(node).getValue());
        assertTrue(Files.exists(checkoutPath.resolve("README.md")));
    }

    @Test
    void resyncWithNoNewCommitsReportsTheSameCommit() throws Exception {
        GitSyncNode node = configuredNode();
        node.process(ProcessContext.uncancelled());
        String firstCommit = commitOutput(node).getValue();

        node.process(ProcessContext.uncancelled());

        assertEquals(firstCommit, commitOutput(node).getValue(),
                "nothing new was pushed, so the second check reports the same commit");
    }

    @Test
    void resyncAfterANewCommitReportsTheNewCommit() throws Exception {
        GitSyncNode node = configuredNode();
        node.process(ProcessContext.uncancelled());
        String firstCommit = commitOutput(node).getValue();
        commitFile(remote, "new-file.txt", "content");

        node.process(ProcessContext.uncancelled());

        assertNotEquals(firstCommit, commitOutput(node).getValue());
        assertTrue(Files.exists(checkoutPath.resolve("new-file.txt")));
    }

    private GitSyncNode configuredNode() {
        GitSyncNode node = new GitSyncNode();
        input(node, "Repository URL").setValue(tempDir.resolve("remote").toUri().toString());
        input(node, "Local Path").setValue(checkoutPath.toString());
        return node;
    }

    @SuppressWarnings("unchecked")
    private static NodeVariable<String> input(GitSyncNode node, String name) {
        return (NodeVariable<String>) node.getInputs().stream()
                .filter(variable -> variable.name.equals(name))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static NodeVariable<String> commitOutput(GitSyncNode node) {
        return (NodeVariable<String>) node.getOutputs().stream()
                .filter(variable -> variable.name.equals("Commit"))
                .findFirst()
                .orElseThrow();
    }

    private static void commitFile(Git git, String name, String content) throws Exception {
        Path file = git.getRepository().getWorkTree().toPath().resolve(name);
        Files.writeString(file, content);
        git.add().addFilepattern(name).call();
        git.commit()
                .setMessage("add " + name)
                .setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com")
                // The sandbox's global git config may turn on commit signing; JGit can't sign
                // with an arbitrary configured format, so force it off for this test-only commit.
                .setSign(false)
                .call();
    }
}
