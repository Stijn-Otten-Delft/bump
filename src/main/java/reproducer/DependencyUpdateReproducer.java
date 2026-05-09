package reproducer;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import miner.DependencyUpdate;
import miner.JsonUtils;
import miner.ReproducibleDependencyUpdate;
import miner.common.DockerConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

/**
 * The BreakingUpdateReproducer class attempts to reproduce breaking updates in a container.
 * In case of a successful reproduction, the resulting container is stored.
 *
 * @author <a href="mailto:gabsko@kth.se">Gabriel Skoglund</a>
 */
public class DependencyUpdateReproducer {

    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private static final Short EXIT_CODE_OK = 0;

    private final ResultManager resultManager;
    private final DockerClient client;

    private final String cacheVolume;

    /**
     * Set up a new BreakingUpdateReproducer creating new Docker images based on {@value miner.common.DockerConstants#BASE_IMAGE}
     *
     * @param resultManager the ResultManager that will store information about reproduction results.
     */
    public DependencyUpdateReproducer(ResultManager resultManager, String cacheVolume) {
        this.resultManager = resultManager;
        this.cacheVolume = cacheVolume;
        DockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withRegistryUrl("https://hub.docker.com")
                .build();
        DockerHttpClient httpClient = new OkDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .sslConfig(clientConfig.getSSLConfig())
                .connectTimeout(30)
                .build();
        client = DockerClientImpl.getInstance(clientConfig, httpClient);
        log.info("Docker client created");

        try {
            ensureBaseMavenImageExists();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Iterate through a list of breaking updates and attempt to reproduce if not already attempted.
     * @param breakingUpdates the list of breaking updates to reproduce.
     */
    public void reproduceAll(File[] breakingUpdates) {
        for (File breakingUpdate : breakingUpdates) {
            try {
                DependencyUpdate bu = JsonUtils.readFromFile(breakingUpdate.toPath(), DependencyUpdate.class);
                reproduce(bu);
            } catch (RuntimeException | InterruptedException e) {
                log.error("An exception occurred while reproducing the breaking update in {}", breakingUpdate.getName(), e);
            }
        }
    }

    /**
     * Attempt to reproduce the given breaking update.
     * @param bu the breaking update to reproduce.
     */
    public void reproduce(DependencyUpdate bu) throws InterruptedException {
        createBaseImageForBreakingUpdate(bu);
        Map<String, String> startedContainers = new HashMap<>();

        int prevAttemptCount = reproducibleSuccessOrFailure(bu, startedContainers, true);

        if (prevAttemptCount == 0) {
            failReproduce(bu, startedContainers);
            return;
        }

        boolean previouslyFailed = prevAttemptCount < 0;
        if (previouslyFailed) prevAttemptCount = -prevAttemptCount;

        int postAttemptCount = reproducibleSuccessOrFailure(bu, startedContainers,  false);

        if (postAttemptCount == 0) {
            failReproduce(bu, startedContainers);
            return;
        }

        boolean postFailed = postAttemptCount < 0;
        if (postFailed) postAttemptCount = -postAttemptCount;

        // we have a reproducible something, create their images
        startedContainers.put("postCommit",
                createImageForCommit(bu, startedContainers.get("postContainer%s".formatted(postAttemptCount - 1)),
                        "post"));
        startedContainers.put("prevCommit",
                createImageForCommit(bu, startedContainers.get("prevContainer%s".formatted(prevAttemptCount - 1)),
                        "pre"));

        if (!previouslyFailed && postFailed) {
            // this is a breaking change
            resultManager.storeResult(bu, startedContainers.get("postCommit"), startedContainers.get("prevCommit"));
        }

        if (previouslyFailed && !postFailed) {
            // this is an unbreaking change
            //todo change the way we store this
            resultManager.storeResult(bu, startedContainers.get("postCommit"), startedContainers.get("prevCommit"));
        }

        if (!previouslyFailed && !postFailed) {
            // this is a non-breaking change
            // todo change the way we store this
            resultManager.storeResult(bu, startedContainers.get("postCommit"), startedContainers.get("prevCommit"));
        }

        if (previouslyFailed && postFailed) {
            // this is a dependency update that was broken before and after
            // todo change the way we store this
            // todo maybe don't save this, this is just a broken project.
            // todo save this someweher tho to debug this tool, as for example the previous time the problem was the java version
            resultManager.storeResult(bu, startedContainers.get("prevCommit"), startedContainers.get("postCommit"));
        }

        // cleanup
        removeContainers(bu, startedContainers.values());
        removeImages(bu, List.of("base", "pre", "post"));
    }

    private void failReproduce(DependencyUpdate du, Map<String, String> startedContainers) {
        // no reproducibility
        //todo look into storing this more explicitly for analysis

        resultManager.saveUnsuccessfulReproductionResult(du);
        removeContainers(du, startedContainers.values());
        removeImages(du, List.of("base"));
    }

    /**
     *
     * @return if the int is positive it's a reproducible success, if it's negative it's a reproducible failure, and if it's 0 there is no reproducibility
     */
    private int reproducibleSuccessOrFailure(DependencyUpdate bu, Map<String, String> startedContainers, boolean isPre) {
        int attemptCountSuccess = reproducibleSuccess(bu, startedContainers, isPre);
        int attemptCountFailure = reproducibleFailure(bu, startedContainers, isPre);

        if(attemptCountSuccess == -1 && attemptCountFailure == -1)
            return 0;

        if (attemptCountSuccess != -1 && attemptCountFailure != -1)
            throw new RuntimeException("This should not happen, we should not have both a reproducible success and a reproducible failure for the same commit");

        if (attemptCountSuccess != -1)
            return attemptCountSuccess;

        return -attemptCountFailure;
    }

    private int reproducibleSuccess(DependencyUpdate bu, Map<String, String> startedContainers, boolean isPre){
        boolean isBuildSuccessful = false;
        int attemptCount;

        // make a lambda that represents getPrevCMd or getPostCmd based on the preOrPost parameter
        String containerCommand = isPre ? getPrevCmd(bu) : getPostCmd(bu);
        String containerName = isPre ? "prevContainer%s" : "postContainer%s";

        String preOrPost = isPre ? "previous" : "post";

        // Try running tests 3 times for the previous commit to ensure that the build is reproducible.
        for (attemptCount = 1; attemptCount < 4; attemptCount++) {
            log.info("Attempting for the {} time to compile and test if the {} commit of breaking update {} is successful",
                    attemptCount, preOrPost, bu.postCommit);
            startedContainers.put(containerName.formatted(attemptCount), startContainer(bu, containerCommand));
            WaitContainerResultCallback result = client.waitContainerCmd(startedContainers.get(containerName
                            .formatted(attemptCount)))
                    .exec(new WaitContainerResultCallback());
            if (result.awaitStatusCode().intValue() != EXIT_CODE_OK) {
                log.info("Build failed for the {} commit of {} in the {} attempt.", preOrPost, bu.postCommit, attemptCount);
                break;
            } else {
                if (attemptCount > 2) {
                    isBuildSuccessful = true;
                }
            }
        }

        if (!isBuildSuccessful) {
            return -1;
        }

        return attemptCount;
    }

    private int reproducibleFailure(DependencyUpdate bu, Map<String, String> startedContainers, boolean isPre) {
        int attemptCount;
        boolean isBuildSuccessfullyFailed = false;
        ReproducibleDependencyUpdate.FailureCategory prevFailure = null;
        ReproducibleDependencyUpdate.FailureCategory newFailure;

        String containerCommand = isPre ? getPrevCmd(bu) : getPostCmd(bu);
        String containerName = isPre ? "prevContainer%s" : "postContainer%s";

        String preOrPost = isPre ? "previous" : "post";

        // Try running tests 3 times to ensure that the breakage is reproducible.
        for (attemptCount = 1; attemptCount < 4; attemptCount++) {
            log.info("Attempting for the {} time to compile and test failure of {} update {}", attemptCount, preOrPost, bu.postCommit);

            startedContainers.put(containerName.formatted(attemptCount), startContainer(bu, containerCommand));
            WaitContainerResultCallback result = client.waitContainerCmd(startedContainers.get(containerName
                    .formatted(attemptCount))).exec(new WaitContainerResultCallback());

            if (result.awaitStatusCode().intValue() != EXIT_CODE_OK) {
                newFailure = resultManager.getFailure(bu,
                        startedContainers.get(containerName.formatted(attemptCount)), true);
                if (attemptCount == 1) {
                    prevFailure = resultManager.getFailure(bu,
                            startedContainers.get(containerName.formatted(attemptCount)), true);
                }
                else if (!newFailure.equals(prevFailure)) {
                    log.info("Build has failed due to a different reason in the {} attempt than in the previous attempt."
                            , attemptCount);
                    if (attemptCount > 1) resultManager.removeLogFile(bu, "successfulReproductionLogs");
                    break;
                } else if (attemptCount > 2) {
                    isBuildSuccessfullyFailed = true;
                }
            } else {
                log.info("Breaking commit did not fail in the {} attempt.", attemptCount);
                // Remove the log file saved in the successful directory in the previous attempts.
                if (attemptCount > 1) resultManager.removeLogFile(bu, "successfulReproductionLogs");
                break;
            }
        }

        if (!isBuildSuccessfullyFailed) {
            return -1;
        }

        return attemptCount;
    }

    /** Remove the containers created during the reproduction of the breaking update */
    private void removeContainers(DependencyUpdate bu, Collection<String> startedContainers) {
        log.info("Removing containers for breaking update {}", bu.postCommit);
        for (String containerId : startedContainers)
            client.removeContainerCmd(containerId).exec();
    }

    /** Remove unwanted images created in intermediate steps when storing results for the breaking update **/
    private void removeImages(DependencyUpdate bu, List<String> extraTags) {
        for (String tag : extraTags) {
            client.removeImageCmd(bu.postCommit + ":" + tag).exec();
        }
    }

    /** Start a container for the given breaking update with a specific command */
    private String startContainer(DependencyUpdate bu, String cmd) {
        if(cacheVolume != null) {
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withBinds(new Bind(cacheVolume, new Volume("/root/.m2")));
            CreateContainerResponse container = client.createContainerCmd(bu.postCommit + ":base")
                    .withWorkingDir("/" + bu.project)
                    .withCmd("sh", "-c", cmd)
                    .withHostConfig(hostConfig)
                    .exec();
            client.startContainerCmd(container.getId()).exec();
            return container.getId();
        }

        CreateContainerResponse container = client.createContainerCmd(bu.postCommit + ":base")
                .withWorkingDir("/" + bu.project)
                .withCmd("sh", "-c", cmd)
                .exec();
        client.startContainerCmd(container.getId()).exec();
        return container.getId();
    }

    /** Command to compile and test the preceding commit of the breaking update */
    private static String getPrevCmd(DependencyUpdate bu) {
        return "set -o pipefail && git checkout %s && git checkout HEAD~1 && rm -rf .git && mvn clean test -B | tee %s.log"
                .formatted(bu.postCommit, bu.postCommit);
    }

    /** Command to compile and test the breaking update */
    private static String getPostCmd(DependencyUpdate bu) {
        return "set -o pipefail && git checkout %s && rm -rf .git && mvn clean test -B | tee %s.log"
                .formatted(bu.postCommit, bu.postCommit);
    }

    /** Command to compile and test the breaking update to be used in the final debloated image */
    private static String getCmd() {
        return "mvn clean test -B";
    }

    /** Ensure that the maven docker image we use as a base exists */
    public void ensureBaseMavenImageExists() throws InterruptedException {
        try {
            client.inspectImageCmd(DockerConstants.BASE_IMAGE).exec();
        } catch (NotFoundException e) {
            log.info("Base image not present, pulling {}", DockerConstants.BASE_IMAGE);
            client.pullImageCmd(DockerConstants.BASE_IMAGE)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion();
            log.info("Done pulling Maven image");
        }
    }

    /** Create a new base docker image for the given breaking update **/
    private void createBaseImageForBreakingUpdate(DependencyUpdate bu) {
        log.info("Creating docker image for breaking update {}", bu.postCommit);
        String projectUrl = bu.url.replaceAll("/pull/\\d+", "");
        CreateContainerResponse container = client.createContainerCmd(DockerConstants.BASE_IMAGE)
                .withCmd("/bin/sh", "-c", "git clone " + projectUrl +
                        " && cd " + bu.project + " && git fetch --depth 2 origin " + bu.postCommit)
                .exec();
        client.startContainerCmd(container.getId()).exec();
        WaitContainerResultCallback waitResult = client.waitContainerCmd(container.getId())
                .exec(new WaitContainerResultCallback());
        if (waitResult.awaitStatusCode().intValue() != EXIT_CODE_OK) {
            log.warn("Could not create docker image for breaking update {}", bu.postCommit);
            throw new RuntimeException(waitResult.toString());
        }
        client.commitCmd(container.getId())
                .withRepository(bu.postCommit)
                .withTag("base").exec();
        log.info("Created docker image for breaking update {}", bu.postCommit);

        client.removeContainerCmd(container.getId()).exec();
    }

    /** Create new docker images for the previous and post commits of the given breaking update **/
    private String createImageForCommit(DependencyUpdate bu, String containerId, String extraTag) {
        //todo maybe check if we used the volume and if so change that here, or maybe not I'm not 100% sure that that would be required"
        client.commitCmd(containerId).withRepository(bu.postCommit).withTag(extraTag).exec();
        CreateContainerResponse container = client.createContainerCmd(bu.postCommit + ":" + extraTag)
                .withWorkingDir("/" + bu.project)
                .withCmd("sh", "-c", getCmd())
                .exec();
        return container.getId();
    }
}
