package miner.common;

public final class DockerConstants {
    public static final String BASE_IMAGE = "ghcr.io/chains-project/breaking-updates:base-image-java-23";
    public static final String DEFAULT_JAVA_VERSION_FOR_REPRODUCTION = "23";

    /**
     * The repository where the created images will be stored
     */
    public static final String REPOSITORY = "ghcr.io/Stijn/breaking-updates";

    /**
     * Tag that will be added as a suffix to breaking update containers containing the state of the repo
     * directly preceding the breaking update commit.
     */
    public static final String PRECEDING_COMMIT_CONTAINER_TAG = "-pre";

    /**
     * Tag that will be added as a suffix to breaking update containers containing the repo at the commit that
     * introduced the breaking update.
     */
    public static final String BREAKING_UPDATE_COMMIT_CONTAINER_TAG = "-breaking";
}
