/**
 * Git repository syncing. {@link io.github.jaymcole.housegraph.plugins.github.GitRepoSync}
 * clones a repository into a local folder on first use and, on every later call, fetches from
 * the remote and fast-forwards the folder onto its tracking branch if it moved. Backed by JGit —
 * no dependency on a system {@code git} binary being installed.
 */
package io.github.jaymcole.housegraph.plugins.github;
