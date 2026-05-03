package reproducer;

import com.fasterxml.jackson.databind.type.MapType;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import miner.*;
import miner.ReproducibleDependencyUpdate.FailureCategory;
import miner.ReproducibleDependencyUpdate.UpdatedDependency.UpdatedFileType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static miner.common.DockerConstants.*;

/**
 * The ResultManager handles storing of reproduction results in the form of logs, jars, Docker images etc.
 *
 * @author <a href="mailto:gabsko@kth.se">Gabriel Skoglund</a>
 */
public class ResultManager {

    private final DockerClient client;
    private final Path benchmarkDir;
    private final Path unsuccessfulReproductionDir;
    private final Path notReproducedDataDir;
    private final Path jarDir;

    private final Path successfulReproductionLogDir;
    private final Path unsuccessfulReproductionLogDir;

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private final GitHubManager gitHubManager;
    private final WorkflowLogFinder workflowLogFinder;
    private final DependencyRefLinkFinder dependencyRefLinkFinder;

    public static final Map<Pattern, FailureCategory> FAILURE_PATTERNS = new HashMap<>();

    static {
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(COMPILATION ERROR|Failed to execute goal io\\.takari\\.maven\\.plugins:takari-lifecycle-plugin.*?:compile)"),
                FailureCategory.COMPILATION_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(\\[ERROR] Tests run:|There are test failures|There were test failures|" +
                        "Failed to execute goal org\\.apache\\.maven\\.plugins:maven-surefire-plugin)"),
                FailureCategory.TEST_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Failed to execute goal org\\.apache\\.maven\\.plugins:maven-enforcer-plugin|" +
                        "Failed to execute goal org\\.jenkins-ci\\.tools:maven-hpi-plugin)"),
                FailureCategory.ENFORCER_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Could not resolve dependencies|\\[ERROR] Some problems were encountered while processing the POMs|" +
                        "\\[ERROR] .*?The following artifacts could not be resolved)"),
                FailureCategory.DEPENDENCY_RESOLUTION_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Failed to execute goal se\\.vandmo:dependency-lock-maven-plugin:.*?:check)"),
                FailureCategory.DEPENDENCY_LOCK_FAILURE);
    }

    /**
     * @param benchmarkDir                the directory where successfully reproduced breaking update JSON files should
     *                                    be written.
     * @param unsuccessfulReproductionDir the directory where unsuccessful breaking update reproduction JSON files
     *                                    should be written.
     * @param notReproducedDataDir        the directory where not reproduced candidate breaking update files are located.
     * @param logDir                      the directory where maven logs should be stored.
     * @param jarDir                      the directory where jar files corresponding to changed dependencies should be
     *                                    stored.
     */
    public ResultManager(Path benchmarkDir, Path unsuccessfulReproductionDir, Path notReproducedDataDir,
                         Path logDir, Path jarDir, GitHubManager gitHubManager,
                         WorkflowLogFinder workflowLogFinder, DependencyRefLinkFinder dependencyRefLinkFinder) {
        this.gitHubManager = gitHubManager;
        this.workflowLogFinder = workflowLogFinder;
        this.dependencyRefLinkFinder = dependencyRefLinkFinder;
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        this.client = DockerClientImpl.getInstance(config,
                new OkDockerHttpClient.Builder().dockerHost(config.getDockerHost()).build());
        this.benchmarkDir = benchmarkDir;
        this.unsuccessfulReproductionDir = unsuccessfulReproductionDir;
        this.notReproducedDataDir = notReproducedDataDir;
        this.jarDir = jarDir;
        successfulReproductionLogDir = logDir.resolve("successfulReproductionLogs");
        unsuccessfulReproductionLogDir = logDir.resolve("unsuccessfulReproductionLogs");
        if (Files.notExists(successfulReproductionLogDir) || Files.notExists(unsuccessfulReproductionLogDir)) {
            try {
                log.info("Creating subdirectories for reproduction logs in {}", logDir);
                Files.createDirectories(successfulReproductionLogDir);
                Files.createDirectories(unsuccessfulReproductionLogDir);
            } catch (IOException e) {
                log.error("Could not create subdirectories for reproduction logs");
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Store the log file of the reproduction attempt.
     */
    private Path storeLogFile(DependencyUpdate bu, String containerId, Boolean isReproducible) {
        // Save log result in reproduction dir.
        Path outputDir = isReproducible ? successfulReproductionLogDir : unsuccessfulReproductionLogDir;
        Path logOutputLocation = outputDir.resolve(bu.postCommit + ".log");
        String logLocation = "/%s/%s.log".formatted(bu.project, bu.postCommit);
        try (InputStream logStream = client.copyArchiveFromContainerCmd(containerId, logLocation).exec()) {
            byte[] fileContent = logStream.readAllBytes();
            Files.write(logOutputLocation, fileContent);
            return logOutputLocation;
        } catch (IOException e) {
            log.error("Could not store the log file for breaking update {}", bu.postCommit);
            throw new RuntimeException(e);
        }
    }

    /**
     * Delete the log file of the reproduction attempt from the wrong directory.
     */
    public void removeLogFile(DependencyUpdate bu, String directory) {
        Path outputDir = directory.equals("successful") ? successfulReproductionLogDir : unsuccessfulReproductionLogDir;
        boolean isRemovingSuccessful = outputDir.resolve(bu.postCommit + ".log").toFile().delete();
        if (!isRemovingSuccessful) log.error("Could not remove the log file from the {} reproduction directory for the "
                + "breaking update {}", directory, bu.postCommit);
    }

    /**
     * Store results when the reproduction is successful.
     */
    public void storeResult(DependencyUpdate bu, String postContainerId, String prevContainerId) {
        Path logOutputLocation = successfulReproductionLogDir.resolve(bu.postCommit + ".log");

        if(gitHubManager != null) {
            // Push the saved log file to the cache repo.
            try {
                byte[] fileContent = Files.readAllBytes(logOutputLocation);
                gitHubManager.pushFiles(bu.postCommit, logOutputLocation.toFile().getName(), fileContent);
            } catch (IOException e) {
                log.error("Failed to push the {} to the cache repo.", logOutputLocation.toFile().getName(), e);
            }
        }

        String githubCompareLink = null;
        String githubSlug = null;
        String mavenSourceLinkPre = null;
        String mavenSourceLinkBreaking = null;
        String dependencyLicenseInfo = null;
        try {
            githubCompareLink = dependencyRefLinkFinder.getGithubCompareLink(bu);
            githubSlug = dependencyRefLinkFinder.getGithubRepository(bu).getName();
            dependencyLicenseInfo = dependencyRefLinkFinder.getGithubRepository(bu).getLicense().getName();
            List<String> mavenSourceLinks = dependencyRefLinkFinder.getMavenSourceLinks(bu);
            if (mavenSourceLinks != null) {
                mavenSourceLinkPre = mavenSourceLinks.get(0);
                mavenSourceLinkBreaking = mavenSourceLinks.get(1);
            }
        } catch (IOException e) {
            log.error("Dependency reference links could not be fetched for the breaking update {}. Therefore, the " +
                    "reference links will be assigned null.", bu.postCommit, e);
        }
        UpdatedFileType updateType = extractDependencies(bu, postContainerId, prevContainerId);
        // Create a new reproducible breaking update object.
        ReproducibleDependencyUpdate reproducibleDU = new ReproducibleDependencyUpdate(bu.url, bu.project, bu.projectOrganisation,
                bu.postCommit, bu.prAuthor, bu.preCommitAuthor, bu.postCommitAuthor, bu.updatedDependency,
                githubCompareLink, mavenSourceLinkPre, mavenSourceLinkBreaking, updateType, bu.licenseInfo, dependencyLicenseInfo, githubSlug);
        // Delete the BreakingUpdateJSON data from the in-progress-reproductions directory.
        removeBreakingUpdateFile(bu);
        // Set the default Java version used for the reproduction.
        reproducibleDU.setJavaVersionUsedForReproduction();
        // Get failure category.
        FailureCategory failureCategory = getFailureCategory(logOutputLocation);
        // Set failure category for the reproducible breaking update.
        reproducibleDU.setFailureCategory(failureCategory);

        // Create docker images.
        log.info("Creating images for breaking update {}", reproducibleDU.postCommit);
        createImage(reproducibleDU, prevContainerId, PRECEDING_COMMIT_CONTAINER_TAG);
        createImage(reproducibleDU, postContainerId, BREAKING_UPDATE_COMMIT_CONTAINER_TAG);

        if(gitHubManager != null) {
            log.info("Pushing the created images for breaking update {}", reproducibleDU.postCommit);
            gitHubManager.pushImage(reproducibleDU, PRECEDING_COMMIT_CONTAINER_TAG);
            gitHubManager.pushImage(reproducibleDU, BREAKING_UPDATE_COMMIT_CONTAINER_TAG);
        }
        storeImageMetadata(reproducibleDU, List.of(PRECEDING_COMMIT_CONTAINER_TAG, BREAKING_UPDATE_COMMIT_CONTAINER_TAG),
                List.of("/root/.m2", "/" + reproducibleDU.project));
        reproducibleDU.setPreCommitReproductionCommand("docker run %s:%s%s".formatted(REPOSITORY, reproducibleDU.postCommit,
                PRECEDING_COMMIT_CONTAINER_TAG));
        reproducibleDU.setBreakingUpdateReproductionCommand("docker run %s:%s%s".formatted(REPOSITORY,
                reproducibleDU.postCommit, BREAKING_UPDATE_COMMIT_CONTAINER_TAG));

        // Add the reproducible breaking update file to the benchmark.
        log.info("Storing result {} for successfully reproduced breaking update {}", failureCategory, reproducibleDU.postCommit);
        JsonUtils.writeToFile(benchmarkDir.resolve(reproducibleDU.postCommit + JsonUtils.JSON_FILE_ENDING),
                reproducibleDU);

        if (workflowLogFinder != null) {
            // Download the workflow log files.
            try {
                workflowLogFinder.extractWorkflowLogFile(bu);
            } catch (IOException e) {
                log.error("Could not download the workflow log files for the BU {}", reproducibleDU.postCommit, e);
            }
        }
        // Delete the local images.
        // todo maybe make this a flag
        deleteImages(reproducibleDU.postCommit);
    }

    /**
     * Remove JSON data from the in-progress-reproductions directory after the reproduction attempt.
     */
    public void removeBreakingUpdateFile(DependencyUpdate bu) {
        log.info("Removing the JSON file from the in-progress-reproductions directory.");
        boolean isRemovingSuccessful = notReproducedDataDir.resolve(bu.postCommit + JsonUtils.JSON_FILE_ENDING)
                .toFile().delete();
        if (!isRemovingSuccessful) log.error("Could not remove the JSON file from the in-progress-reproductions directory.");
    }

    /**
     * Save breaking update JSON data in unsuccessful-reproductions dir when the reproduction is unsuccessful.
     */
    public void saveUnsuccessfulReproductionResult(DependencyUpdate bu) {
        var unreproducibleDU = new UnreproducibleDependencyUpdate(bu);

        // Delete the BreakingUpdateJSON data from the in-progress-reproductions directory.
        removeBreakingUpdateFile(bu);
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
     * Analyze the log file to identify the reproduction label.
     *
     * @param path the path of the log file.
     */
    private FailureCategory getFailureCategory(Path path) {
        try {
            String logContent = Files.readString(path, StandardCharsets.ISO_8859_1);
            for (Map.Entry<Pattern, FailureCategory> entry : FAILURE_PATTERNS.entrySet()) {
                Pattern pattern = entry.getKey();
                Matcher matcher = pattern.matcher(logContent);
                if (matcher.find()) {
                    return entry.getValue();
                }
            }
            return FailureCategory.UNKNOWN_FAILURE;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the first failure category in the first reproduction attempt failure.
     */
    public FailureCategory getFailure(DependencyUpdate bu, String containerId, Boolean isReproducible) {
        Path logOutputLocation = storeLogFile(bu, containerId, isReproducible);
        return getFailureCategory(logOutputLocation);
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
                "failure_category", bu.getFailureCategory().name()
        );
        client.commitCmd(containerId).withRepository(REPOSITORY).withTag(bu.postCommit + extraTag)
                .withLabels(labels).exec();
    }

    /**
     * Store image metadata for successfully created images. Image metadata includes size of the all downloaded
     * dependencies for the project (.m2 folder) and the size of the project after cloning.
     */
    public void storeImageMetadata(ReproducibleDependencyUpdate bu, List<String> tags, List<String> folderPaths) {
        Map<String, String> reproduction_metadata = new HashMap<>();
        for (int tagCount = 0; tagCount < tags.size(); tagCount++) {
            for (String folderPath : folderPaths) {
                CreateContainerResponse container = client.createContainerCmd(REPOSITORY + ":" + bu.postCommit +
                        tags.get(tagCount)).withCmd("/bin/sh", "-c", "tail -f /dev/null").exec();
                client.startContainerCmd(container.getId()).exec();
                // Execute the `du` command inside the container to get the folder size.
                String[] command = {"/bin/sh", "-c", "du -s " + folderPath};
                ExecCreateCmdResponse execCreateCmdResponse = client.execCreateCmd(container.getId())
                        .withAttachStdout(true)
                        .withCmd(command)
                        .exec();
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                try {
                    client.execStartCmd(execCreateCmdResponse.getId())
                            .exec(new ExecStartResultCallback(outputStream, System.err))
                            .awaitCompletion();
                    // Extract the folder size from the command output.
                    String[] commandOutput = outputStream.toString(StandardCharsets.UTF_8).trim().split("\\s+");
                    if (folderPath.contains("m2")) {
                        reproduction_metadata.put((tagCount < 1) ? "prevImageM2FolderSize" : "postImageM2FolderSize",
                                String.valueOf(commandOutput[0]));
                    } else {
                        reproduction_metadata.put((tagCount < 1) ? "prevImageProjectFolderSize" : "postImageProjectFolderSize",
                                String.valueOf(commandOutput[0]));
                    }
                } catch (InterruptedException e) {
                    log.error("Failed to get the folder size of the folder {} inside the image {} for the " +
                            "breaking update {}.", folderPath, REPOSITORY + ":" + bu.postCommit + tags.get(tagCount), bu.postCommit, e);
                }
                client.stopContainerCmd(container.getId()).exec();
                client.removeContainerCmd(container.getId()).exec();
            }
        }
        try {
            MapType jsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
            Path imageMetadataFilePath = Path.of("image_metadata" + JsonUtils.JSON_FILE_ENDING);
            if (Files.notExists(imageMetadataFilePath)) {
                Files.createFile(imageMetadataFilePath);
            }
            Map<String, Map<String, String>> imageMetadata = JsonUtils.readFromNullableFile(imageMetadataFilePath, jsonType);
            if (imageMetadata == null) {
                imageMetadata = new HashMap<>();
            }
            imageMetadata.put(bu.postCommit, reproduction_metadata);
            JsonUtils.writeToFile(imageMetadataFilePath, imageMetadata);
            log.info("Successfully stored the image metadata for the breaking update {} in {}\\image_metadata.json file.",
                    bu.postCommit, successfulReproductionLogDir);
        } catch (RuntimeException | IOException e) {
            log.error("Failed to store the image metadata for the breaking update {}.", bu.postCommit, e);
        }
    }

    /**
     * Delete the local images after pushing to the GitHub packages
     */
    private void deleteImages(String buCommit) {
        client.removeImageCmd(REPOSITORY + ":" + buCommit + PRECEDING_COMMIT_CONTAINER_TAG).withForce(true).exec();
        client.removeImageCmd(REPOSITORY + ":" + buCommit + BREAKING_UPDATE_COMMIT_CONTAINER_TAG).withForce(true).exec();
    }
}
