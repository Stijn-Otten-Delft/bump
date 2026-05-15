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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static reproducer.DependencyUpdateType.*;

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
    private final FailureLogManager failureLogManager;
    private final DockerClient client;

    private final String cacheVolume;
    private final Integer parallel;

    private static final String PrevFailContainerName = "prevFailContainer%s";
    private static final String PrevSucContainerName = "prevSucContainer%s";
    private static final String PostFailContainerName = "postFailContainer%s";
    private static final String PostSucContainerName = "postSucContainer%s";

    /**
     * Set up a new BreakingUpdateReproducer creating new Docker images based on {@value miner.common.DockerConstants#BASE_IMAGE}
     *
     * @param resultManager the ResultManager that will store information about reproduction results.
     */
    public DependencyUpdateReproducer(ResultManager resultManager, FailureLogManager failureLogManager, String cacheVolume, Integer parallel) {
        this.resultManager = resultManager;
        this.failureLogManager = failureLogManager;
        this.cacheVolume = cacheVolume;
        this.parallel = parallel;
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
     * Uses a fixed thread pool for efficient parallel execution of I/O-bound reproduction tasks.
     * @param dependencyUpdates the list of breaking updates to reproduce.
     */
    public void reproduceAll(File[] dependencyUpdates) {
        var list = getDependencyUpdates(dependencyUpdates);

        if(parallel != null) {
            reproduceParallel(list);
        }

        else {
            int total = list.size();
            int done = 0;

            for (var breakingUpdate : list) {
                reproduceOne(breakingUpdate);

                done++;
                log.info("Completed {}/{} reproduction tasks", done, total);
            }
        }
    }

    private void reproduceParallel(List<DependencyUpdate> dependencyUpdates){
        ExecutorService executorService = Executors.newFixedThreadPool(parallel);
        CountDownLatch latch = new CountDownLatch(dependencyUpdates.size());
        AtomicInteger completed = new AtomicInteger(0);
        int total = dependencyUpdates.size();

        log.info("Starting parallel reproduction of {} dependency updates with {} threads", total, parallel);

        for (DependencyUpdate breakingUpdate : dependencyUpdates) {
            executorService.submit(() -> {
                try {
                    reproduceOne(breakingUpdate);
                } finally {
                    int done = completed.incrementAndGet();
                    log.info("Completed {}/{} reproduction tasks", done, total);
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
            log.info("All {} reproduction tasks completed", total);
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for all reproduction tasks to complete", e);
            Thread.currentThread().interrupt();
        } finally {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.warn("Executor service did not terminate within timeout, forcing shutdown");
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for executor service to terminate", e);
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void reproduceOne(DependencyUpdate dependencyUpdate){
        try{
             reproduce(dependencyUpdate);
        }  catch (RuntimeException | InterruptedException e) {
            log.error("An exception occurred while reproducing the dependency in {}", dependencyUpdate.postCommit, e);
        }
    }

    private List<DependencyUpdate> getDependencyUpdates(File[] dependencyUpdates){
        var updateList = new ArrayList<DependencyUpdate>(dependencyUpdates.length);

        for (File dependencyUpdate : dependencyUpdates) {
            try {
                DependencyUpdate du = JsonUtils.readFromFile(dependencyUpdate.toPath(), DependencyUpdate.class);
                updateList.add(du);
            } catch (RuntimeException e) {
                log.error("An exception occurred while converting the json to class in {}", dependencyUpdate.getName(), e);
            }
        }

        return updateList;
    }

    /**
     * Attempt to reproduce the given breaking update.
     * @param du the breaking update to reproduce.
     */
    public void reproduce(DependencyUpdate du) throws InterruptedException {
        createBaseImageForBreakingUpdate(du);
        Map<String, String> startedContainers = new HashMap<>();

        int prevAttemptCount = reproducibleSuccessOrFailure(du, startedContainers, true);

        if (prevAttemptCount == 0) {
            failReproduce(du, startedContainers);
            return;
        }

        boolean previouslyFailed = prevAttemptCount < 0;
        if (previouslyFailed) prevAttemptCount = -prevAttemptCount;

        int postAttemptCount = reproducibleSuccessOrFailure(du, startedContainers,  false);

        if (postAttemptCount == 0) {
            failReproduce(du, startedContainers);
            return;
        }

        boolean postFailed = postAttemptCount < 0;
        if (postFailed) postAttemptCount = -postAttemptCount;

        String lastPostContainerId = (postFailed) ?
                startedContainers.get(PostFailContainerName.formatted(postAttemptCount - 1)) :
                startedContainers.get(PostSucContainerName.formatted(postAttemptCount - 1));

        String lastPrevContainerId = (previouslyFailed) ?
                startedContainers.get(PrevFailContainerName.formatted(prevAttemptCount - 1)) :
                startedContainers.get(PrevSucContainerName.formatted(prevAttemptCount - 1));

        //String lastPostContainerId = startedContainers.get("postContainer%s".formatted(postAttemptCount - 1));
        //String lastPrevContainerId = startedContainers.get("prevContainer%s".formatted(prevAttemptCount - 1));


        DependencyUpdateType duType;
        if(previouslyFailed && postFailed) duType = ALWAYS_FAILING;
        else if(previouslyFailed && !postFailed) duType = FIXING;
        else if(!previouslyFailed && postFailed) duType = BREAKING;
        else duType = NO_CHANGE;

        resultManager.storeDependencyUpdateResult(du, lastPostContainerId, lastPrevContainerId, duType);

        // cleanup
        removeContainers(du, startedContainers.values());
        removeImages(du, List.of("base"));
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
        if (attemptCountSuccess != -1) return attemptCountSuccess;

        int attemptCountFailure = reproducibleFailure(bu, startedContainers, isPre);
        if(attemptCountFailure != -1) return -attemptCountFailure;

        return 0;
    }

    private int reproducibleSuccess(DependencyUpdate bu, Map<String, String> startedContainers, boolean isPre){
        boolean isBuildSuccessful = false;
        int attemptCount;

        // make a lambda that represents getPrevCMd or getPostCmd based on the preOrPost parameter
        String containerCommand = isPre ? getPrevCmd(bu) : getPostCmd(bu);
        String containerName = isPre ? PrevSucContainerName : PostSucContainerName;

        String preOrPost = isPre ? "previous" : "post";

        // Try running tests 3 times for the previous commit to ensure that the build is reproducible.
        for (attemptCount = 1; attemptCount < 4; attemptCount++) {
            log.info("Attempting for the {} time see if {} update {} passes the build and tests",
                    attemptCount, preOrPost, bu.postCommit);

            WaitContainerResultCallback result;

            if(attemptCount == 1) {
                // easy way to synchronize this part
                // it needs to synchronize since the first run might write to the maven cache docker volume
                synchronized (this){
                    String containerId = startContainer(bu, containerCommand);
                    startedContainers.put(containerName.formatted(attemptCount), containerId);

                    result = client.waitContainerCmd(containerId).exec(new WaitContainerResultCallback());
                }
            }else{
                String containerId = startContainer(bu, containerCommand);
                startedContainers.put(containerName.formatted(attemptCount), containerId);

                result = client.waitContainerCmd(containerId).exec(new WaitContainerResultCallback());
            }


            if (result.awaitStatusCode().intValue() != EXIT_CODE_OK) {
                log.info("Build or test failed for the {} commit of {} in the {} attempt.", preOrPost, bu.postCommit, attemptCount);
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

    private int reproducibleFailure(DependencyUpdate du, Map<String, String> startedContainers, boolean isPre) {
        //if we every want both pre- and post-fail information the logging here needs to change to tag the logs with the pre- and post-tag

        int attemptCount;
        boolean isBuildSuccessfullyFailed = false;
        ReproducibleDependencyUpdate.FailureCategory prevFailure = null;
        ReproducibleDependencyUpdate.FailureCategory newFailure;

        String containerCommand = isPre ? getPrevCmd(du) : getPostCmd(du);
        String containerName = isPre ? PrevFailContainerName : PostFailContainerName;

        String preOrPost = isPre ? "previous" : "post";

        // Try running tests 3 times to ensure that the breakage is reproducible.
        for (attemptCount = 1; attemptCount < 4; attemptCount++) {
            log.info("Attempting for the {} time to see if {} update {} fails the build and tests", attemptCount, preOrPost, du.postCommit);

            String containerId = startContainer(du, containerCommand);
            startedContainers.put(containerName.formatted(attemptCount), containerId);

            WaitContainerResultCallback result = client.waitContainerCmd(containerId)
                    .exec(new WaitContainerResultCallback());

            if (result.awaitStatusCode().intValue() != EXIT_CODE_OK) {
                newFailure = failureLogManager.storeAndGetFailure(du,
                        startedContainers.get(containerName.formatted(attemptCount)), isPre);
                if (attemptCount == 1) {
                    prevFailure = failureLogManager.storeAndGetFailure(du,
                            startedContainers.get(containerName.formatted(attemptCount)), isPre);
                }
                else if (!newFailure.equals(prevFailure)) {
                    log.info("Build of {} commit {} has failed due to a different reason in the {} attempt than in the previous attempt."
                            , preOrPost, du.postCommit, attemptCount);
                    if (attemptCount > 1) failureLogManager.removeLogFile(du, isPre);
                    break;
                } else if (attemptCount > 2) {
                    isBuildSuccessfullyFailed = true;
                }
            } else {
                log.info("{} commit {} did not fail in the {} attempt.", preOrPost, du.postCommit, attemptCount);
                // Remove the log file saved in the successful directory in the previous attempts.
                if (attemptCount > 1) failureLogManager.removeLogFile(du, isPre);
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
            client.removeImageCmd(bu.postCommit + ":" + tag).withForce(true).exec();
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
}
