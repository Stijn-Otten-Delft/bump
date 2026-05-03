package miner;

import java.nio.file.Path;
import java.util.Date;

/**
 * The RepositorySearchConfig contains information used when finding suitable repositories.
 *
 * @param minNumberOfStars        the minimum numbers of stars the repository should have.
 * @param earliestCreationDate    the earliest allowed creation date for the repository.
 * @param minNumberOfCommits      the minimum numbers of commits the repository should have.
 * @param minNumberOfContributors the minimum numbers of contributors the repository should have.
 */
public record RepositorySearchConfig(int minNumberOfStars, Date earliestCreationDate, int minNumberOfCommits,
                                     int minNumberOfContributors) {

    public static RepositorySearchConfig fromJson(Path jsonFile) {
        return JsonUtils.readFromFile(jsonFile, RepositorySearchConfig.class);
    }
}
