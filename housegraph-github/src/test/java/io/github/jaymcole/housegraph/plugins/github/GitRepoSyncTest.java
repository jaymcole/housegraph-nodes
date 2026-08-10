package io.github.jaymcole.housegraph.plugins.github;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link GitRepoSync} end-to-end against a local, file-based "remote" repository - no
 * network or GitHub access needed, and fast enough to run on every build.
 */
class GitRepoSyncTest {

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
    void firstSyncClonesIntoAnEmptyFolder() throws Exception {
        GitRepoSync.Result result = GitRepoSync.sync(remoteUri(), checkoutPath);

        assertTrue(result.changed(), "cloning into an empty folder counts as a change");
        assertTrue(Files.exists(checkoutPath.resolve("README.md")));
    }

    @Test
    void resyncWithNoNewCommitsReportsNoChange() throws Exception {
        GitRepoSync.Result first = GitRepoSync.sync(remoteUri(), checkoutPath);

        GitRepoSync.Result second = GitRepoSync.sync(remoteUri(), checkoutPath);

        assertFalse(second.changed(), "nothing new was pushed, so the second sync is a no-op");
        assertEquals(first.commitId(), second.commitId());
    }

    @Test
    void resyncAfterANewCommitPullsIt() throws Exception {
        GitRepoSync.sync(remoteUri(), checkoutPath);
        commitFile(remote, "new-file.txt", "content");

        GitRepoSync.Result result = GitRepoSync.sync(remoteUri(), checkoutPath);

        assertTrue(result.changed());
        assertTrue(Files.exists(checkoutPath.resolve("new-file.txt")), "the new commit's file must land in the checkout");
    }

    @Test
    void syncRefusesANonEmptyNonGitFolder() throws IOException {
        Files.createDirectories(checkoutPath);
        Files.writeString(checkoutPath.resolve("existing.txt"), "already here");

        assertThrows(IOException.class, () -> GitRepoSync.sync(remoteUri(), checkoutPath));
    }

    private String remoteUri() {
        return tempDir.resolve("remote").toUri().toString();
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
