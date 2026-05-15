package reproducer;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import miner.*;
import miner.ReproducibleDependencyUpdate.FailureCategory;
import miner.ReproducibleDependencyUpdate.UpdatedDependency.UpdatedFileType;

import org.kohsuke.github.GHRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static miner.common.DockerConstants.*;
import static reproducer.DependencyUpdateType.*;

/**
 * The ResultManager handles storing of reproduction results in the form of logs, jars, Docker images etc.
 *
 * @author <a href="mailto:gabsko@kth.se">Gabriel Skoglund</a>
 */
public class ResultManager {

    private final DockerClient client;
    private final Path unsuccessfulReproductionDir;
    private final Path notYetReproducedDataDir;
    private final Path alreadyReproducedDataDir;
    private final Path jarDir;
    private final boolean deleteImages;

    private final Path reproductionBreakingDir;
    private final Path reproductionNoChangeDir;
    private final Path reproductionFixingDir;
    private final Path reproductionAlwaysFailDir;

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private final GitHubManager gitHubManager;
    private final WorkflowLogFinder workflowLogFinder;
    private final DependencyRefLinkFinder dependencyRefLinkFinder;
    private final ImageMetadataManager imageMetadataManager;
    private final FailureLogManager failureLogManager;

    private static final String BreakingSubDir = "breaking";
    private static final String NoChangeSubDir = "noChange";
    private static final String FixingSubDir = "fixing";
    private static final String AlwaysFailSubDir = "alwaysFail";

    /**
     * @param unsuccessfulReproductionDir the directory where unsuccessful breaking update reproduction JSON files
     *                                    should be written.
     * @param notYetReproducedDataDir        the directory where not reproduced candidate breaking update files are located.
     * @param jarDir                      the directory where jar files corresponding to changed dependencies should be
     *                                    stored.
     */
    public ResultManager(Path unsuccessfulReproductionDir, Path notYetReproducedDataDir,
                         Path alreadyReproducedDataDir, FailureLogManager failureLogManager, Path jarDir, GitHubManager gitHubManager,
                         Boolean deleteImages, WorkflowLogFinder workflowLogFinder,
                         DependencyRefLinkFinder dependencyRefLinkFinder,
                         Path reproductionBreakingDir, Path reproductionFixingDir, Path reproductionNoChangeDir,
                         Path reproductionAlwaysFailDir) {
        this.reproductionBreakingDir = reproductionBreakingDir;
        this.reproductionNoChangeDir = reproductionNoChangeDir;
        this.reproductionFixingDir = reproductionFixingDir;

        this.reproductionAlwaysFailDir = reproductionAlwaysFailDir;

        this.gitHubManager = gitHubManager;
        this.deleteImages = deleteImages;
        this.workflowLogFinder = workflowLogFinder;
        this.dependencyRefLinkFinder = dependencyRefLinkFinder;
        this.failureLogManager = failureLogManager;
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        this.client = DockerClientImpl.getInstance(config,
                new OkDockerHttpClient.Builder().dockerHost(config.getDockerHost()).build());

        this.unsuccessfulReproductionDir = unsuccessfulReproductionDir;
        this.notYetReproducedDataDir = notYetReproducedDataDir;
        this.alreadyReproducedDataDir = alreadyReproducedDataDir;
        this.jarDir = jarDir;

        if(alreadyReproducedDataDir != null) {
            checkIfPathExistOrCreate(alreadyReproducedDataDir.resolve(BreakingSubDir));
            checkIfPathExistOrCreate(alreadyReproducedDataDir.resolve(FixingSubDir));
            checkIfPathExistOrCreate(alreadyReproducedDataDir.resolve(NoChangeSubDir));
            checkIfPathExistOrCreate(alreadyReproducedDataDir.resolve(AlwaysFailSubDir));
        }

        this.imageMetadataManager = new ImageMetadataManager(client);
    }

    private void checkIfPathExistOrCreate(Path path) {
        if (Files.notExists(path)) {
            try {
                log.info("Creating directory {}", path);
                Files.createDirectories(path);
            } catch (IOException e) {
                log.error("Could not create directory {}", path);
                throw new RuntimeException(e);
            }
        }
    }


    /**
     * Store results when the reproduction finds a breaking update
     */
    public void storeDependencyUpdateResult(DependencyUpdate du, String postContainerId, String prevContainerId, String lastPostContainerId, String lastPrevContainerId, DependencyUpdateType duType) {
        if(duType == ALWAYS_FAILING && reproductionAlwaysFailDir == null) { //todo fix
            log.warn("We are not storing the result for the dependency update {} that fails both before and after the update since no reproduction-always-fail-dir was provided.",
                    du.postCommit);

            if(alreadyReproducedDataDir != null) {
                moveDependencyUpdateFile(du, AlwaysFailSubDir);
            }else {
                log.info("`The dependency update {} will be removed without any reproduction date stored", du.postCommit);
                removeDependencyUpdateFile(du);
            }
            return;
        }

        if(gitHubManager != null) {
            // Push the saved log file to the cache repo.
            throw new UnsupportedOperationException("Pushing to github in general currently only works for specifically breaking updates as the original idea was for BUMP");
        }

        String githubCompareLink = "A GitHub repository could not be found for the updated dependency.";
        String githubSlug = null;
        String mavenSourceLinkPre = null;
        String mavenSourceLinkBreaking = null;
        String dependencyLicenseInfo = null;

        synchronized (this) {
            try {
                GHRepository repository = dependencyRefLinkFinder.getGithubRepository(du);
                githubCompareLink = dependencyRefLinkFinder.getGithubCompareLink(repository, du);
                githubSlug = repository.getName();
                dependencyLicenseInfo = repository.getLicense().getName();
            } catch (IOException e) {
                log.error("Could not get GH repository for {}", du.postCommit); //todo make this log more obvious what github we were trying to find
            }

            List<String> mavenSourceLinks = dependencyRefLinkFinder.getMavenSourceLinks(du);
            if (mavenSourceLinks != null) {
                mavenSourceLinkPre = mavenSourceLinks.get(0);
                mavenSourceLinkBreaking = mavenSourceLinks.get(1);
            }
        }

        UpdatedFileType updateType = extractDependencies(du, lastPostContainerId, lastPrevContainerId);

        //from here on out it might need the change depending on what type of dependency update it is

        // Create a new reproducible breaking update object.
        ReproducibleDependencyUpdate reproducibleDU = new ReproducibleDependencyUpdate(du,
                githubCompareLink, mavenSourceLinkPre, mavenSourceLinkBreaking, updateType, dependencyLicenseInfo, githubSlug);;


        //todo make pre and post fail at the same time work
        //set the failure category if it exists
        if(preFail || postFail) {
            Path logOutputLocation = successfulReproductionLogDir.resolve(du.postCommit + ".log");
            // Get failure category.
            FailureCategory failureCategory = getFailureCategory(logOutputLocation);

            if(preFail) reproducibleDU.setPreFailureCategory(failureCategory);
            else reproducibleDU.setPostFailureCategory(failureCategory);
        }

        String preImageTag = PRECEDING_COMMIT_CONTAINER_TAG + (duType == ALWAYS_FAILING || duType == FIXING ? BREAKING_UPDATE_COMMIT_CONTAINER_TAG : "");
        String postImageTag = POST_COMMIT_CONTAINER_TAG + (duType == ALWAYS_FAILING || duType == BREAKING ? BREAKING_UPDATE_COMMIT_CONTAINER_TAG : "");

        // Create docker images.
        log.info("Creating images for breaking update {}", reproducibleDU.postCommit);
        createImage(reproducibleDU, prevContainerId, preImageTag, reproducibleDU.getPreFailureCategory());
        createImage(reproducibleDU, postContainerId, postImageTag, reproducibleDU.getPostFailureCategory());

        // no GitHub stuff rn

        imageMetadataManager.storeImageMetadata(reproducibleDU, preImageTag, postImageTag);

        reproducibleDU.setPreCommitReproductionCommand("docker run %s:%s%s".formatted(REPOSITORY, reproducibleDU.postCommit,
                preImageTag));
        reproducibleDU.setPostUpdateReproductionCommand("docker run %s:%s%s".formatted(REPOSITORY,
                reproducibleDU.postCommit, postImageTag));

        // Add the reproducible breaking update file to the benchmark.
        log.info("Storing result for successfully reproduced dependency update {}", reproducibleDU.postCommit);


        Path reproDir = switch (duType) {
            case ALWAYS_FAILING -> reproductionAlwaysFailDir;
            case FIXING -> reproductionFixingDir;
            case BREAKING -> reproductionBreakingDir;
            case NO_CHANGE -> reproductionNoChangeDir;
        };

        JsonUtils.writeToFile(reproDir.resolve(reproducibleDU.postCommit + JsonUtils.JSON_FILE_ENDING),
                reproducibleDU);

        // Delete the BreakingUpdateJSON data from the in-progress-reproductions directory.
        if(alreadyReproducedDataDir != null) {
            switch (duType) {
                case ALWAYS_FAILING -> moveDependencyUpdateFile(du, AlwaysFailSubDir);
                case FIXING -> moveDependencyUpdateFile(du, FixingSubDir);
                case BREAKING -> moveDependencyUpdateFile(du, BreakingSubDir);
                case NO_CHANGE -> moveDependencyUpdateFile(du, NoChangeSubDir);
            }
        } else {
            removeDependencyUpdateFile(du);
        }

        if(deleteImages){
            deleImage(reproducibleDU.postCommit, preImageTag);
            deleImage(reproducibleDU.postCommit, postImageTag);
        }
    }

//    /**
//     * Store results when the reproduction is successful.
//     */
//    public void storeResult(DependencyUpdate bu, String postContainerId, String prevContainerId, String lastPostContainerId, String lastPrevContainerId) {
//        //todo this erally only works with a breaking failure rn due to it checking for a breking log file
//        Path logOutputLocation = successfulReproductionLogDir.resolve(bu.postCommit + ".log");
//
//        if(gitHubManager != null) {
//            // Push the saved log file to the cache repo.
//            try {
//                byte[] fileContent = Files.readAllBytes(logOutputLocation);
//                gitHubManager.pushFiles(bu.postCommit, logOutputLocation.toFile().getName(), fileContent);
//            } catch (IOException e) {
//                log.error("Failed to push the {} to the cache repo.", logOutputLocation.toFile().getName(), e);
//            }
//        }
//
//        String githubCompareLink = null;
//        String githubSlug = null;
//        String mavenSourceLinkPre = null;
//        String mavenSourceLinkBreaking = null;
//        String dependencyLicenseInfo = null;
//        //todo change these oders since their failures don't always imply eachothe
//        try {
//            githubCompareLink = dependencyRefLinkFinder.getGithubCompareLink(bu);
//            githubSlug = dependencyRefLinkFinder.getGithubRepository(bu).getName();
//            dependencyLicenseInfo = dependencyRefLinkFinder.getGithubRepository(bu).getLicense().getName();
//            List<String> mavenSourceLinks = dependencyRefLinkFinder.getMavenSourceLinks(bu);
//            if (mavenSourceLinks != null) {
//                mavenSourceLinkPre = mavenSourceLinks.get(0);
//                mavenSourceLinkBreaking = mavenSourceLinks.get(1);
//            }
//        } catch (Exception e) {
//            log.error("Dependency reference links could not be fetched for the breaking update {}. Therefore, the " +
//                    "reference links will be assigned null.", bu.postCommit, e);
//        }
//        UpdatedFileType updateType = extractDependencies(bu, lastPostContainerId, lastPrevContainerId);
//        // Create a new reproducible breaking update object.
//        ReproducibleDependencyUpdate reproducibleDU = new ReproducibleDependencyUpdate(bu,
//                githubCompareLink, mavenSourceLinkPre, mavenSourceLinkBreaking, updateType, dependencyLicenseInfo, githubSlug);
//        // Delete the BreakingUpdateJSON data from the in-progress-reproductions directory.
//        removeBreakingUpdateFile(bu);
//        // Set the default Java version used for the reproduction.
//        reproducibleDU.setJavaVersionUsedForReproduction();
//        // Get failure category.
//        FailureCategory failureCategory = getFailureCategory(logOutputLocation);
//        // Set failure category for the reproducible breaking update.
//        reproducibleDU.setPostFailureCategory(failureCategory);
//
//        // Create docker images.
//        log.info("Creating images for breaking update {}", reproducibleDU.postCommit);
//        createImage(reproducibleDU, prevContainerId, PRECEDING_COMMIT_CONTAINER_TAG);
//        createImage(reproducibleDU, postContainerId, BREAKING_UPDATE_COMMIT_CONTAINER_TAG);
//
//        if(gitHubManager != null) {
//            log.info("Pushing the created images for breaking update {}", reproducibleDU.postCommit);
//            gitHubManager.pushImage(reproducibleDU, PRECEDING_COMMIT_CONTAINER_TAG);
//            gitHubManager.pushImage(reproducibleDU, BREAKING_UPDATE_COMMIT_CONTAINER_TAG);
//        }
//        imageMetadataManager.storeImageMetadata(reproducibleDU, List.of(PRECEDING_COMMIT_CONTAINER_TAG, BREAKING_UPDATE_COMMIT_CONTAINER_TAG),
//                List.of("/root/.m2", "/" + reproducibleDU.project));
//        reproducibleDU.setPreCommitReproductionCommand("docker run %s:%s%s".formatted(REPOSITORY, reproducibleDU.postCommit,
//                PRECEDING_COMMIT_CONTAINER_TAG));
//        reproducibleDU.setPostUpdateReproductionCommand("docker run %s:%s%s".formatted(REPOSITORY,
//                reproducibleDU.postCommit, BREAKING_UPDATE_COMMIT_CONTAINER_TAG));
//
//        // Add the reproducible breaking update file to the benchmark.
//        log.info("Storing result {} for successfully reproduced breaking update {}", failureCategory, reproducibleDU.postCommit);
//        JsonUtils.writeToFile(benchmarkDir.resolve(reproducibleDU.postCommit + JsonUtils.JSON_FILE_ENDING),
//                reproducibleDU);
//
//        if (workflowLogFinder != null) {
//            // Download the workflow log files.
//            try {
//                workflowLogFinder.extractWorkflowLogFile(bu);
//            } catch (IOException e) {
//                log.error("Could not download the workflow log files for the BU {}", reproducibleDU.postCommit, e);
//            }
//        }
//
//        // Delete the local images.
//        if(deleteImages) {
//            deleteImages(reproducibleDU.postCommit);
//        }
//    }

    /**
     * Remove JSON data from the in-progress-reproductions directory after the reproduction attempt.
     */
    public void removeDependencyUpdateFile(DependencyUpdate bu) {
        log.info("Removing the JSON file from the in-progress-reproductions directory.");
        boolean isRemovingSuccessful = notYetReproducedDataDir.resolve(bu.postCommit + JsonUtils.JSON_FILE_ENDING)
                .toFile().delete();
        if (!isRemovingSuccessful) log.error("Could not remove the JSON file from the in-progress-reproductions directory.");
    }

    public void moveDependencyUpdateFile(DependencyUpdate bu, boolean x) {
        log.info("Moving the JSON file from the in-progress-reproductions directory.");
        Path sourcePath = notYetReproducedDataDir.resolve(bu.postCommit + JsonUtils.JSON_FILE_ENDING);
        Path targetPath = alreadyReproducedDataDir.resolve(bu.postCommit + JsonUtils.JSON_FILE_ENDING);
        try {
            Files.move(sourcePath, targetPath);
        } catch (IOException e) {
            log.error("Could not move the JSON file to the already-reproduced directory for breaking update {}", bu.postCommit);
        }
    }

    public void moveDependencyUpdateFile(DependencyUpdate bu, String subDir) {
        log.info("Moving the JSON file from the in-progress-reproductions directory.");
        Path sourcePath = notYetReproducedDataDir.resolve(subDir).resolve(bu.postCommit + JsonUtils.JSON_FILE_ENDING);
        Path targetPath = alreadyReproducedDataDir.resolve(subDir).resolve(bu.postCommit + JsonUtils.JSON_FILE_ENDING);
        try {
            Files.move(sourcePath, targetPath);
        } catch (IOException e) {
            log.error("Could not move the JSON file to the already-reproduced directory for breaking update {}", bu.postCommit);
        }
    }

    /**
     * Save breaking update JSON data in unsuccessful-reproductions dir when the reproduction is unsuccessful.
     */
    public void saveUnsuccessfulReproductionResult(DependencyUpdate bu) {
        var unreproducibleDU = new UnreproducibleDependencyUpdate(bu);

        // Delete the BreakingUpdateJSON data from the in-progress-reproductions directory.
        removeDependencyUpdateFile(bu);
        log.info("Saving the JSON file containing an unreproducible breaking update {} in unsuccessful-reproductions " +
                "dir.", unreproducibleDU.postCommit);
        // Update breaking update file.
        JsonUtils.writeToFile(unsuccessfulReproductionDir.resolve(unreproducibleDU.postCommit +
                JsonUtils.JSON_FILE_ENDING), unreproducibleDU);
    }

    /**
     * Save breaking update JSON data in unsuccessful-reproductions dir when the reproduction is unsuccessful.
     */
    public void savePreAndPostFailureReproductionResult(DependencyUpdate bu) {
        var unreproducibleDU = new UnreproducibleDependencyUpdate(bu);

        // Delete the BreakingUpdateJSON data from the in-progress-reproductions directory.
        removeDependencyUpdateFile(bu);
        log.info("Saving the JSON file containing an unreproducible breaking update {} in unsuccessful-reproductions " +
                "dir.", unreproducibleDU.postCommit);
        // Update breaking update file.
        JsonUtils.writeToFile(unsuccessfulReproductionDir.resolve(unreproducibleDU.postCommit +
                JsonUtils.JSON_FILE_ENDING), unreproducibleDU);
    }

    /**
     * Copy old/new pair of dependency jar/pom files from the corresponding containers.
     *
     * @return the type of the updated dependency.
     */
    private UpdatedFileType extractDependencies(DependencyUpdate bu, String postContainerId,
                                                String prevContainerId) {
        String dependencyLocationBase = "/root/.m2/repository/%s/%s/"
                .formatted(bu.updatedDependency.dependencyGroupID.replaceAll("\\.", "/"),
                        bu.updatedDependency.dependencyArtifactID);
        for (String type : List.of("jar", "pom")) {
            UpdatedFileType updateType = UpdatedFileType.valueOf(type.toUpperCase(Locale.ENGLISH));
            String oldDependencyLocation = dependencyLocationBase + "%s/%s-%s.%s"
                    .formatted(bu.updatedDependency.previousVersion, bu.updatedDependency.dependencyArtifactID,
                            bu.updatedDependency.previousVersion, type);
            try (InputStream dependencyStream = client.copyArchiveFromContainerCmd
                    (prevContainerId, oldDependencyLocation).exec()) {
                Path dir = Files.createDirectories(jarDir
                        .resolve(bu.updatedDependency.dependencyGroupID.replaceAll("\\.", "/"))
                        .resolve(bu.updatedDependency.previousVersion));
                String fileName = "%s-%s.%s".formatted(bu.updatedDependency.dependencyArtifactID,
                        bu.updatedDependency.previousVersion, type);
                byte[] fileContent = dependencyStream.readAllBytes();
                Files.write(dir.resolve(fileName), fileContent);

                if(gitHubManager != null) {
                    // Push the saved old jar/pom file to the cache repo.
                    String jarName = "%s__%s__%s___prev.%s".formatted(bu.updatedDependency.dependencyGroupID, bu.updatedDependency
                            .dependencyArtifactID, bu.updatedDependency.previousVersion, type);
                    gitHubManager.pushFiles(bu.postCommit, jarName, fileContent);
                }
            } catch (NotFoundException e) {
                if (type.equals("jar")) {
                    log.info("Could not find the old jar for breaking update {}. Searching for a pom instead...",
                            bu.postCommit);
                } else {
                    log.error("Could not find the old jar or pom for breaking update {}", bu.postCommit);
                }
                continue;
            } catch (IOException e) {
                log.error("Could not store the old {} for breaking update {}.", type, bu.postCommit, e);
            }

            String newDependencyLocation = dependencyLocationBase + "%s/%s-%s.%s"
                    .formatted(bu.updatedDependency.newVersion, bu.updatedDependency.dependencyArtifactID,
                            bu.updatedDependency.newVersion, type);
            try (InputStream dependencyStream = client.copyArchiveFromContainerCmd(postContainerId,
                    newDependencyLocation).exec()) {
                Path dir = Files.createDirectories(jarDir
                        .resolve(bu.updatedDependency.dependencyGroupID.replaceAll("\\.", "/"))
                        .resolve(bu.updatedDependency.newVersion));
                String fileName = "%s-%s.%s".formatted(bu.updatedDependency.dependencyArtifactID,
                        bu.updatedDependency.newVersion, type);
                byte[] fileContent = dependencyStream.readAllBytes();
                Files.write(dir.resolve(fileName), fileContent);

                if(gitHubManager != null) {
                    // Push the saved new jar/pom file to the cache repo.
                    String jarName = "%s__%s__%s___new.%s".formatted(bu.updatedDependency.dependencyGroupID, bu.updatedDependency
                            .dependencyArtifactID, bu.updatedDependency.newVersion, type);
                    gitHubManager.pushFiles(bu.postCommit, jarName, fileContent);
                    return updateType;
                }
            } catch (NotFoundException e) {
                if (type.equals("jar")) {
                    log.error("Could not find the new jar for breaking update {}, even if the old jar exists.",
                            bu.postCommit);
                    return updateType;
                } else {
                    log.error("Could not find the new pom for breaking update {}, even if the old pom exists.",
                            bu.postCommit);
                }
            } catch (IOException e) {
                log.error("Could not store the new {} for breaking update {}.", type, bu.postCommit, e);
            }
            return updateType;
        }
        return null;
    }

    /**
     * Create a new image with the changes of a breaking update reproduction container.
     */
    private void createImage(ReproducibleDependencyUpdate bu, String containerId, String extraTag) {
        Map<String, String> labels = Map.of(
                "github_repository", bu.project,
                "pr_url", bu.url,
                "updated_dependency", bu.updatedDependency.dependencyGroupID + "/" +
                        bu.updatedDependency.dependencyArtifactID,
                "new_version", bu.updatedDependency.newVersion,
                "previous_version", bu.updatedDependency.previousVersion,
                "failure_category", bu.getPostFailureCategory().name() //todo AAAAAAH
        );
        client.commitCmd(containerId).withRepository(REPOSITORY).withTag(bu.postCommit + extraTag)
                .withLabels(labels).exec();
    }

    /**
     * Create a new image with the changes of a breaking update reproduction container.
     */
    private void createImage(ReproducibleDependencyUpdate bu, String containerId, String extraTag, FailureCategory failureCategory) {
        HashMap<String, String> labels = new HashMap<>(Map.of(
                "github_repository", bu.project,
                "pr_url", bu.url,
                "updated_dependency", bu.updatedDependency.dependencyGroupID + "/" +
                        bu.updatedDependency.dependencyArtifactID,
                "new_version", bu.updatedDependency.newVersion,
                "previous_version", bu.updatedDependency.previousVersion
        ));

        if(failureCategory != null) {
            labels.put("failure_category", failureCategory.name());
        }

        client.commitCmd(containerId).withRepository(REPOSITORY).withTag(bu.postCommit + extraTag)
                .withLabels(labels).exec();
    }

    /**
     * Delete the local images after pushing to the GitHub packages
     */
    private void deleteImages(String buCommit) {
        client.removeImageCmd(REPOSITORY + ":" + buCommit + PRECEDING_COMMIT_CONTAINER_TAG).withForce(true).exec();
        client.removeImageCmd(REPOSITORY + ":" + buCommit + BREAKING_UPDATE_COMMIT_CONTAINER_TAG).withForce(true).exec();
    }

    private void deleImage(String buCommit, String imageTag){
        client.removeImageCmd(REPOSITORY + ":" + buCommit + imageTag).withForce(true).exec();
    }
}
