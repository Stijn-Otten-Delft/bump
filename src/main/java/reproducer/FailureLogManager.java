package reproducer;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import miner.DependencyUpdate;
import miner.ReproducibleDependencyUpdate.FailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FailureLogManager {
    private static final Logger log = LoggerFactory.getLogger(FailureLogManager.class);

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

    private final DockerClient client;

    private final Path successfulReproductionLogDir;
    private final Path unsuccessfulReproductionLogDir;

    public FailureLogManager(Path logDir){
        successfulReproductionLogDir = logDir.resolve("successfulReproductionLogs");
        unsuccessfulReproductionLogDir = logDir.resolve("unsuccessfulReproductionLogs");

        checkIfPathExistOrCreate(successfulReproductionLogDir);
        checkIfPathExistOrCreate(unsuccessfulReproductionLogDir);


        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();

        this.client = DockerClientImpl.getInstance(config,
                new OkDockerHttpClient.Builder().dockerHost(config.getDockerHost()).build());
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
     * Get the first failure category in the first reproduction attempt failure.
     */
    public FailureCategory getFailure(DependencyUpdate bu, String containerId, Boolean isReproducible) {
        Path logOutputLocation = storeLogFile(bu, containerId, isReproducible);
        return getFailureCategory(logOutputLocation);
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
}
