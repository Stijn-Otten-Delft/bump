package miner;

import miner.common.DockerConstants;

public class ReproducibleDependencyUpdate extends DependencyUpdate {

    public String preCommitReproductionCommand = null;
    public String postUpdateReproductionCommand = null;
    public String javaVersionUsedForReproduction = DockerConstants.DEFAULT_JAVA_VERSION_FOR_REPRODUCTION;
    public final UpdatedDependency updatedDependency;
    private FailureCategory preFailureCategory;
    private FailureCategory postFailureCategory;

    private int prevImageProjectFolderSize;
    private int postImageProjectFolderSize;


    /**
     * Create a new ReproducibleDependencyUpdate object that stores information about a
     * reproducible dependency update.
     */
    public ReproducibleDependencyUpdate(String url, String project, String projectOrganisation, String postCommit,
                                        String prAuthor, String preCommitAuthor, String postCommitAuthor,
                                        DependencyUpdate.UpdatedDependency updatedDependency, String githubCompareLink,
                                        String mavenSourceLinkPre, String mavenSourceLinkPost,
                                        UpdatedDependency.UpdatedFileType updatedFileType, String licenseInfo, String dependencyLicenseInfo, String githubRepoSlug) {
        super(url, project, projectOrganisation, postCommit, prAuthor, preCommitAuthor, postCommitAuthor, updatedDependency, licenseInfo);
        this.updatedDependency = new UpdatedDependency(updatedDependency.dependencyGroupID, updatedDependency.dependencyArtifactID,
                updatedDependency.previousVersion, updatedDependency.newVersion, updatedDependency.dependencyScope,
                updatedDependency.versionUpdateType, updatedDependency.dependencySection, githubCompareLink, mavenSourceLinkPre,
                mavenSourceLinkPost, updatedFileType, dependencyLicenseInfo, githubRepoSlug);
    }

    public ReproducibleDependencyUpdate(DependencyUpdate du, String githubCompareLink, String mavenSourceLinkPre,
                                        String mavenSourceLinkPost, UpdatedDependency.UpdatedFileType updatedFileType,
                                        String dependencyLicenseInfo, String githubRepoSlug){
        super(du.url, du.project, du.projectOrganisation, du.postCommit, du.prAuthor, du.preCommitAuthor, du.postCommitAuthor, du.updatedDependency, du.licenseInfo);
        this.updatedDependency = new UpdatedDependency(du.updatedDependency, githubCompareLink, mavenSourceLinkPre,
                mavenSourceLinkPost, updatedFileType, dependencyLicenseInfo, githubRepoSlug);
    }

    /**
     * Set the java version used in the reproduction process.
     *
     * @param javaVersionUsedForReproduction the java version used in the reproduction process.
     */
    public void setJavaVersionUsedForReproduction(String javaVersionUsedForReproduction) {
        this.javaVersionUsedForReproduction = javaVersionUsedForReproduction;
    }

    /**
     * Set the default java version used in the reproduction process.
     */
    public void setJavaVersionUsedForReproduction() {
        this.javaVersionUsedForReproduction = DockerConstants.DEFAULT_JAVA_VERSION_FOR_REPRODUCTION;
    }

    /**
     * Update preCommitReproductionCommand of this dependency update.
     *
     * @param preCommitReproductionCommand the new preCommitReproductionCommand to add to this dependency update.
     */
    public void setPreCommitReproductionCommand(String preCommitReproductionCommand) {
        this.preCommitReproductionCommand = preCommitReproductionCommand;
    }

    /**
     * Get preCommitReproductionCommand of this dependency update.
     *
     * @return preCommitReproductionCommand of this dependency update.
     */
    public String getPreCommitReproductionCommand() {
        return preCommitReproductionCommand;
    }

    /**
     * Update failureCategory of this dependency update.
     *
     * @param failureCategory the failureCategory to add to this dependency update.
     */
    public void setPostFailureCategory(FailureCategory failureCategory) {
        this.postFailureCategory = failureCategory;
    }

    /**
     * Get failureCategory of this dependency update.
     *
     * @return failureCategory of this dependency update.
     */
    public FailureCategory getPostFailureCategory() {
        return postFailureCategory;
    }

    public void setPreFailureCategory(FailureCategory preFailureCategory) {
        this.preFailureCategory = preFailureCategory;
    }

    public FailureCategory getPreFailureCategory() {
        return preFailureCategory;
    }

    /**
     * Update postUpdateReproductionCommand of this dependency update.
     *
     * @param postUpdateReproductionCommand the new postUpdateReproductionCommand to add to this dependency update.
     */
    public void setPostUpdateReproductionCommand(String postUpdateReproductionCommand) {
        this.postUpdateReproductionCommand = postUpdateReproductionCommand;
    }

    /**
     * Get postUpdateReproductionCommand of this dependency update.
     *
     * @return postUpdateReproductionCommand of this dependency update.
     */
    public String getPostUpdateReproductionCommand() {
        return postUpdateReproductionCommand;
    }

    public void setPrevImageProjectFolderSize(int prevImageProjectFolderSize) {
        this.prevImageProjectFolderSize = prevImageProjectFolderSize;
    }

    public void setPostImageProjectFolderSize(int postImageProjectFolderSize) {
        this.postImageProjectFolderSize = postImageProjectFolderSize;
    }

    /**
     * Failure category indicating the status of the reproduction, i.e. the results of attempted reproduction.
     */
    public enum FailureCategory {

        /**
         * There were unknown failures after updating the dependency, but none in the previous commit.
         */
        UNKNOWN_FAILURE,
        /**
         * There were failures when downloading dependencies after updating the dependency.
         */
        DEPENDENCY_RESOLUTION_FAILURE,
        /**
         * There were failures when updating the dependency because the dependency version is locked by a
         * dependency-lock plugin.
         */
        DEPENDENCY_LOCK_FAILURE,
        /**
         * The compilation failed due to failing enforcer rules after updating the dependency,
         * but in the previous commit there were no failures.
         */
        ENFORCER_FAILURE,
        /**
         * The compilation failed after updating the dependency, but succeeded for the previous commit.
         */
        COMPILATION_FAILURE,
        /**
         * There were test failures after updating the dependency, but not for the preceding commit.
         */
        TEST_FAILURE
    }

    /**
     * UpdatedDependency represents information associated with the updated dependency.
     */
    public static class UpdatedDependency extends DependencyUpdate.UpdatedDependency {

        public final String githubCompareLink;
        public final String mavenSourceLinkPre;
        public final String mavenSourceLinkPost;
        public final UpdatedFileType updatedFileType;
        public final String licenseInfo;
        public final String githubRepoSlug;

        /**
         * Create updated dependency for the dependency update.
         */
        public UpdatedDependency(String dependencyGroupID, String dependencyArtifactID, String previousVersion,
                                 String newVersion, String dependencyScope, String versionUpdateType, String dependencySection,
                                 String githubCompareLink, String mavenSourceLinkPre, String mavenSourceLinkPost,
                                 UpdatedFileType updatedFileType, String licenseInfo, String githubRepoSlug) {
            super(dependencyGroupID, dependencyArtifactID, previousVersion, newVersion, dependencyScope, versionUpdateType,
                    dependencySection);
            this.githubCompareLink = githubCompareLink;
            this.mavenSourceLinkPre = mavenSourceLinkPre;
            this.mavenSourceLinkPost = mavenSourceLinkPost;
            this.updatedFileType = updatedFileType;
            this.licenseInfo = licenseInfo;
            this.githubRepoSlug = githubRepoSlug;
        }

        public UpdatedDependency(DependencyUpdate.UpdatedDependency ud, String githubCompareLink, String mavenSourceLinkPre,
                                 String mavenSourceLinkPost, UpdatedFileType updatedFileType, String licenseInfo, String githubRepoSlug){
            super(ud.dependencyGroupID, ud.dependencyArtifactID, ud.previousVersion, ud.newVersion, ud.dependencyScope, ud.versionUpdateType, ud.dependencySection);

            this.githubCompareLink = githubCompareLink;
            this.mavenSourceLinkPre = mavenSourceLinkPre;
            this.mavenSourceLinkPost = mavenSourceLinkPost;
            this.updatedFileType = updatedFileType;
            this.licenseInfo = licenseInfo;
            this.githubRepoSlug = githubRepoSlug;
        }

        /**
         * The type of the updated dependency, indicating whether it is a pom type dependency where a jar file will
         * not be collected, or a jar type dependency where a jar file will be downloaded.
         */
        public enum UpdatedFileType {
            POM,
            JAR,
        }
    }
}
