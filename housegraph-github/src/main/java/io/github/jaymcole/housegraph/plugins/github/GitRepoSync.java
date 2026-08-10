package io.github.jaymcole.housegraph.plugins.github;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Keeps a local folder in sync with a git remote: {@link #sync} clones it on first use, and on
 * every later call fetches the remote and — if its tracking branch moved — hard-resets the
 * folder onto the new tip.
 * <p>
 * This is a reset, not a merge: {@code sync} is meant for an unattended checkout that something
 * else runs off of (a deploy directory, a config repo), where "match the remote" should always
 * win over local drift rather than risk a stuck merge conflict. Anything uncommitted in the
 * folder is discarded on a change.
 */
public final class GitRepoSync {

    private GitRepoSync() {
    }

    /** The outcome of one {@link #sync} call. */
    public record Result(boolean changed, String commitId) {
    }

    /**
     * Brings {@code localPath} up to date with {@code repositoryUrl}: clones into it if it isn't
     * already a git checkout, otherwise fetches and resets onto the remote tracking branch if it
     * moved.
     *
     * @param repositoryUrl the repository to sync from
     * @param localPath     the folder to clone into / keep in sync
     * @return whether the folder's contents changed, and the commit it now points at
     * @throws IOException      if the folder is occupied by something that isn't this repository,
     *                          or the sync couldn't be completed
     * @throws GitAPIException  if the underlying git operation fails
     */
    public static Result sync(String repositoryUrl, Path localPath) throws IOException, GitAPIException {
        if (isExistingCheckout(localPath)) {
            return pull(localPath);
        }
        return clone(repositoryUrl, localPath);
    }

    private static boolean isExistingCheckout(Path localPath) {
        return Files.isDirectory(localPath.resolve(".git"));
    }

    private static Result clone(String repositoryUrl, Path localPath) throws IOException, GitAPIException {
        if (Files.isDirectory(localPath) && !isEmpty(localPath)) {
            throw new IOException("Local path " + localPath + " already exists and is neither empty nor a git checkout");
        }
        Files.createDirectories(localPath);
        try (Git git = Git.cloneRepository()
                .setURI(repositoryUrl)
                .setDirectory(localPath.toFile())
                .call()) {
            return new Result(true, headId(git.getRepository()));
        }
    }

    private static Result pull(Path localPath) throws IOException, GitAPIException {
        try (Git git = Git.open(localPath.toFile())) {
            Repository repository = git.getRepository();
            String before = headId(repository);

            git.fetch().setRemote("origin").call();

            String branch = repository.getBranch();
            ObjectId remoteHead = repository.resolve("refs/remotes/origin/" + branch);
            if (remoteHead == null) {
                throw new IOException("No origin/" + branch + " tracking branch found for " + localPath);
            }
            String after = remoteHead.getName();
            if (after.equals(before)) {
                return new Result(false, before);
            }

            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(after).call();
            return new Result(true, after);
        }
    }

    private static String headId(Repository repository) throws IOException {
        ObjectId head = repository.resolve("HEAD");
        return head == null ? null : head.getName();
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }
}
