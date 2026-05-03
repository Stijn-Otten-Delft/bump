package miner;

import miner.common.PathConstants;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import org.kohsuke.github.*;
import org.kohsuke.github.connector.GitHubConnectorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * The GitHubMiner class allows for the mining of GitHub repositories for relevant data.
 * It is currently set up to look for PRs that update a dependency and break the automated build process.
 *
 * @author <a href="mailto:gabsko@kth.se">Gabriel Skoglund</a>
 */
public class GitHubMiner {


    private final OkHttpClient httpConnector;
    private final GitHubAPITokenQueue tokenQueue;
    private final Path outputDirectory;
    private final static Logger log = LoggerFactory.getLogger(GitHubMiner.class);

    /**
     * @param tokenQueue a collection of GitHub API tokens.
     * @param outputDirectory a path to the directory where found breaking updates will be stored.
     * @throws IOException if there is an issue connecting to the GitHub servers.
     */
    public GitHubMiner(GitHubAPITokenQueue tokenQueue, Path outputDirectory) throws IOException {
        this.outputDirectory = outputDirectory;
        // We use OkHttp with a 10 MB cache for HTTP requests
        Cache cache = new Cache(PathConstants.CACHE_DIR, 10 * 1024 * 1024);
        httpConnector = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .cache(cache).build();
        this.tokenQueue = tokenQueue;
        String apiToken = tokenQueue.getToken();
        GitPatchCache.initialize(httpConnector, apiToken);
    }


    /**
     * Query the given GitHub repositories for pull requests that changes a
     * single line in a pom.xml file and breaks a GitHub action workflow.
     *
     * @param repoList a {@link RepositoryList} containing the repositories to mine.
     * @throws IOException if there is an issue when interacting with the file system.
     */
   public void mineRepositories(RepositoryList repoList) throws IOException {
        // We want to limit the number of threads we create so that each API token is allocated
        // to one thread. This is in line with the recommendations from
        // https://docs.github.com/en/rest/overview/resources-in-the-rest-api#secondary-rate-limits
        // In order to do this, we create our own ForkJoinPool instead of relying on the default one.

        List<String> unprocessedRepos = new ArrayList<>();
        List<String> processedRepos = new ArrayList<>();

        repoList.getRepositoryNames().forEach(repo -> {
            if (repoList.getCheckedTime(repo) == null) {
                unprocessedRepos.add(repo);
            } else {
                processedRepos.add(repo);
            }
        });
        mine(repoList, unprocessedRepos);
        mine(repoList, processedRepos);
    }

    private void mine(RepositoryList repoList, List<String> repos) {
        ForkJoinPool threadPool = new ForkJoinPool(tokenQueue.size());
        try {
            threadPool.submit(() -> repos.parallelStream().forEach(repo -> {
                try {
                    mineRepo(repo, repoList.getCheckedTime(repo));
                } catch (IOException e) {
                    log.error("Got IOException: ", e);
                    log.info("Sleeping for 60 seconds");
                    try {
                        TimeUnit.SECONDS.sleep(60);
                    } catch (InterruptedException ex) {
                        log.info("Failed to mine from {}", repo);
                    }
                }
                repoList.setCheckedTime(repo, Date.from(Instant.now()));
                repoList.writeToFile();
            })).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        } finally {
            threadPool.shutdown();
        }
    }

    /**
     * Iterate over all pull requests of a repo added after a given date and save ones that contain breaking updates
     */
    private void mineRepo(String repo, Date cutoffDate) throws IOException {
        try {
            log.info("Checking {}", repo);
            GHRepository repository = tokenQueue.getGitHub(httpConnector).getRepository(repo);
            PagedIterator<GHPullRequest> pullRequests = repository.queryPullRequests()
                    .state(GHIssueState.ALL)
                    .sort(GHPullRequestQueryBuilder.Sort.CREATED)
                    .direction(GHDirection.DESC)
                    .list().iterator();

            while (pullRequests.hasNext()) {
                List<GHPullRequest> nextPage = pullRequests.nextPage();
                if (PullRequestFilters.createdBefore(cutoffDate).test(nextPage.get(0))) {
                    log.info("Checked all PRs for {} created after {}", repo, cutoffDate);
                    break;
                }
                nextPage.stream()
                        .takeWhile(PullRequestFilters.createdBefore(cutoffDate).negate())
                        .filter(PullRequestFilters.changesOnlyDependencyVersionInPomXML)
                        //.filter(PullRequestFilters.breaksBuild)
                        .map(DependencyUpdate::new)
                        .forEach(breakingUpdate -> {
                            if (!breakingUpdate.updatedDependency.dependencyScope.equals("test")) {
                                writeBreakingUpdate(breakingUpdate);
                                log.info("    Found {}", breakingUpdate.url);
                            }
                        });
            }
        } catch (IOException e) {
            log.error("Got IOException while mining {}", repo, e);
            throw e;
        } catch (Exception e) {
            log.error("Got exception while mining {}", repo, e);
            log.info("Skipping {} instead of failing", repo);
        }
    }

    /**
     * Create a json file containing information about a breaking update.
     */
    public void writeBreakingUpdate(DependencyUpdate dependencyUpdate) {
        Path path = outputDirectory.resolve(dependencyUpdate.postCommit + JsonUtils.JSON_FILE_ENDING);
        JsonUtils.writeToFile(path, dependencyUpdate);
    }

    /**
     * The MinerRateLimitChecker helps ensure that the miner does not exceed the GitHub API
     * rate limit. For more information see
     * <a href="https://docs.github.com/en/rest/guides/best-practices-for-integrators#dealing-with-rate-limits">
     * the GitHub API documentation.
     * </a>
     */
    static class MinerRateLimitChecker extends RateLimitChecker {
        private static final int REMAINING_CALLS_CUTOFF = 5;
        private final String apiToken;

        public MinerRateLimitChecker(String apiToken) {
            this.apiToken = apiToken;
        }

        @Override
        protected boolean checkRateLimit(GHRateLimit.Record rateLimitRecord, long count) throws InterruptedException {
            if (rateLimitRecord.getRemaining() < REMAINING_CALLS_CUTOFF) {
                long timeToSleep = rateLimitRecord.getResetDate().getTime() - System.currentTimeMillis();
                System.out.printf("Rate limit exceeded for token %s, sleeping %ds until %s\n",
                                  apiToken, timeToSleep / 1000, rateLimitRecord.getResetDate());
                Thread.sleep(timeToSleep);
                return true;
            }
            return false;
        }
    }

    /**
     * The MinerGitHubAbuseLimitHandler determines what to do in case we exceed the
     * GitHub API abuse limit
     */
    static class MinerGitHubAbuseLimitHandler extends GitHubAbuseLimitHandler {
        private static final int timeToSleepMillis = 60_000;
        private final String apiToken;

        public MinerGitHubAbuseLimitHandler(String apiToken) {
            this.apiToken = apiToken;
        }

        @Override
        public void onError(GitHubConnectorResponse connectorResponse) throws IOException {
            System.out.println(new String(connectorResponse.bodyStream().readAllBytes()));
            System.out.printf("Abuse limit reached for token %s, sleeping %d seconds\n",
                              apiToken, timeToSleepMillis / 1000);
            try {
                Thread.sleep(timeToSleepMillis);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
