package miner;

import miner.common.PathConstants;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GitHubFinder {
    private final static Logger log = LoggerFactory.getLogger(GitHubFinder.class);

    private final OkHttpClient httpConnector;

    private final GitHubAPITokenQueue tokenQueue;

    public GitHubFinder(GitHubAPITokenQueue tokenQueue) {
        this.tokenQueue = tokenQueue;

        Cache cache = new Cache(PathConstants.CACHE_DIR, 10 * 1024 * 1024);
        this.httpConnector = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .cache(cache).build();
    }

    /**
     * Query GitHub for repositories that are Maven projects and has
     * GitHub actions that are run on pull requests. The found repositories
     * will be stored in a file called found_repositories in the specified
     * output directory.
     * <p>
     * Since GitHub will only return at most 1000 results per API call,
     * and searching is restricted to a tighter API rate limit,
     * this method will attempt to perform sequential queries using different
     * API tokens until the full search result has been returned.
     *
     * @param repoList a {@link RepositoryList} of previously found repositories.
     * @param searchConfig a {@link RepositorySearchConfig} specifying the repositories to look for.
     * @throws IOException if there is an issue when interacting with the file system.
     */
    public void findRepositories(RepositoryList repoList, RepositorySearchConfig searchConfig, Date lastDate, int maxRepos) throws IOException {
        log.info("Finding valid repositories");
        int previousSize = repoList.size();
        LocalDate creationDate = lastDate !=null ? lastDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : LocalDate.now(ZoneId.systemDefault());
        PagedSearchIterable<GHRepository> search = searchForRepos(searchConfig.minNumberOfStars(), creationDate);

        LocalDate earliestCreationDate =
                searchConfig.earliestCreationDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        while (creationDate.isAfter(earliestCreationDate) && (repoList.size() - previousSize) < maxRepos) {
            log.info("Checking repos created on {} ", creationDate);
            PagedIterator<GHRepository> iterator = search.iterator();
            while (iterator.hasNext() && (repoList.size() - previousSize) < maxRepos) {
                List<GHRepository> validRepos = iterator.nextPage().stream()
                        .filter(repository -> !repoList.contains(repository))
                        .peek(repository -> System.out.println("  Checking " + repository.getFullName()))
                        .filter(RepositoryFilters.isMavenProject)
                        .filter(RepositoryFilters.hasPullRequestWorkflows)
                        .filter(repository -> RepositoryFilters.hasSufficientNumberOfCommits(repository,
                                searchConfig.minNumberOfCommits()))
                        .filter(repository -> RepositoryFilters.hasSufficientNumberOfContributors(repository,
                                searchConfig.minNumberOfContributors()))
                        .toList();

                for (GHRepository repository : validRepos) {
                    if ((repoList.size() - previousSize) >= maxRepos) {
                        break;
                    }
                    repoList.add(repository);
                    log.info("  Found {}", repository.getUrl());
                }
            }
            creationDate = creationDate.minusDays(1);
            search = searchForRepos(searchConfig.minNumberOfStars(), creationDate);
            repoList.writeToFile();
        }
        log.info("Found {} valid repositories", repoList.size() - previousSize);
    }

    /**
     * Search for GitHub repos that use Java as the main language, having the required minimum number
     * of stars and having been created at the given date. Forks will be ignored and the result will
     * be sorted based on the number of stars, descending.
     */
    private PagedSearchIterable<GHRepository> searchForRepos(int minNumberOfStars, LocalDate creationDate)
            throws IOException {
        return tokenQueue.getGitHub(httpConnector).searchRepositories()
                .language("Java")
                .fork(GHFork.PARENT_ONLY)
                .stars(">=" + minNumberOfStars)
                .created(creationDate.toString())
                .sort(GHRepositorySearchBuilder.Sort.STARS)
                .order(GHDirection.DESC)
                .list();
    }

}
