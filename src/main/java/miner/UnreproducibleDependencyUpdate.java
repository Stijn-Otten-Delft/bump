package miner;

public class UnreproducibleDependencyUpdate extends DependencyUpdate {

    private static final String DEFAULT_JAVA_VERSION_FOR_REPRODUCTION = "11";
    public String javaVersionUsedForReproduction;

    /**
     * Create a new UnreproducibleBreakingUpdate object that stores information about an
     * unreproducible breaking dependency update.
     */
    public UnreproducibleDependencyUpdate(String url, String project, String projectOrganisation, String breakingCommit,
                                          String prAuthor, String preCommitAuthor, String breakingCommitAuthor,
                                          DependencyUpdate.UpdatedDependency updatedDependency, String licenseInfo) {
        super(url, project, projectOrganisation, breakingCommit, prAuthor, preCommitAuthor, breakingCommitAuthor, updatedDependency, licenseInfo);
    }

    public UnreproducibleDependencyUpdate(DependencyUpdate bu){
        super(bu.url, bu.project, bu.projectOrganisation, bu.postCommit, bu.prAuthor, bu.preCommitAuthor, bu.postCommitAuthor, bu.updatedDependency, bu.licenseInfo);
        this.javaVersionUsedForReproduction = DEFAULT_JAVA_VERSION_FOR_REPRODUCTION;
    }

    public UnreproducibleDependencyUpdate(DependencyUpdate bu, String javaVersionUsedForReproduction){
        super(bu.url, bu.project, bu.projectOrganisation, bu.postCommit, bu.prAuthor, bu.preCommitAuthor, bu.postCommitAuthor, bu.updatedDependency, bu.licenseInfo);
        this.javaVersionUsedForReproduction = javaVersionUsedForReproduction;
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
        this.javaVersionUsedForReproduction = DEFAULT_JAVA_VERSION_FOR_REPRODUCTION;
    }
}
