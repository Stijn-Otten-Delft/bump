package reproducer;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.PushImageResultCallback;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import miner.GitHubAPITokenQueue;
import miner.ReproducibleDependencyUpdate;
import miner.common.GitConstants;
import okhttp3.OkHttpClient;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class GitHubManager {
    private final GitHubAPITokenQueue tokenQueue;
    private final GitHubPackagesCredentials registryCredentials;

    private final DockerClient client;
    private final OkHttpClient httpConnector;
    private final Logger log = LoggerFactory.getLogger(this.getClass());


    /**
     *
     * @param tokenQueue                   a queue of GitHub API tokens.
     * @param registryCredentials         the directory where jar files corresponding to changed dependencies should be
     *                                    stored.
     */
    public GitHubManager(GitHubAPITokenQueue tokenQueue, GitHubPackagesCredentials registryCredentials) throws IOException {
        this.tokenQueue = tokenQueue;
        this.registryCredentials = registryCredentials;

        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        this.client = DockerClientImpl.getInstance(config,
                new OkDockerHttpClient.Builder().dockerHost(config.getDockerHost()).build());

        httpConnector = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS).build();
    }

    /**
     * Push an image to GitHub packages using the provided credentials.
     */
    public void pushImage(ReproducibleDependencyUpdate bu, String extraTag) {
        try {
            AuthConfig authConfig = new AuthConfig()
                    .withUsername(registryCredentials.userName())
                    .withPassword(registryCredentials.identityToken())
                    .withRegistryAddress(GitConstants.REPOSITORY);
            client.pushImageCmd(GitConstants.REPOSITORY)
                    .withTag(bu.postCommit + extraTag)
                    .withAuthConfig(authConfig)
                    .exec(new PushImageResultCallback())
                    .awaitCompletion();
        } catch (Exception e) {
            log.error("Failed to push the image {} to GitHub packages.", bu.postCommit + extraTag, e);
        }
    }

    /**
     * Push a given log file or a jar/pom file to the GitHub repo breaking-updates-cache.
     */
    public void pushFiles(String breakingCommit, String fileName, byte[] fileContent) {
        try {
            GitHub github = tokenQueue.getGitHub(httpConnector);
            GHRepository repo = github.getRepository(GitConstants.CACHE_REPO);
            GHRef branchRef = repo.getRef("heads/" + GitConstants.BRANCH_NAME);
            String latestCommitHash = branchRef.getObject().getSha();
            // Create the tree.
            GHTreeBuilder treeBuilder = repo.createTree();
            treeBuilder.baseTree(latestCommitHash);
            treeBuilder.add("data/" + breakingCommit + "/" + fileName, fileContent, false);
            GHTree tree = treeBuilder.create();
            // Create the commit.
            GHCommit commit = repo.createCommit()
                    .message("Push the %s for the breaking update %s.".formatted(fileName, breakingCommit))
                    .parent(latestCommitHash)
                    .tree(tree.getSha())
                    .create();
            // Update the branch reference.
            branchRef.updateTo(commit.getSHA1());
            log.info("Successfully pushed the {} to the {}.", fileName, GitConstants.CACHE_REPO);
        } catch (IOException e) {
            log.error("Failed to push the {} to the {}.", fileName, GitConstants.CACHE_REPO, e);
        } catch (GHException e) {
            log.error("The provided GitHub token does not have the permission to push the {} to the {}",
                    fileName, GitConstants.CACHE_REPO, e);
        }
    }
}
