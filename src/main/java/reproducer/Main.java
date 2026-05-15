package reproducer;

import miner.DependencyUpdate;
import miner.GitHubAPITokenQueue;
import miner.JsonUtils;
import org.jspecify.annotations.NonNull;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
/**
 * This class represents the main entry point to the breaking update reproducer.
 *
 * @author <a href="mailto:gabsko@kth.se">Gabriel Skoglund</a>
 * <p>
 */
public class Main {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Reproduce()).execute(args);
        System.exit(exitCode);
    }

    @CommandLine.Command(name = "reproduce", mixinStandardHelpOptions = true, version = "0.1")
    private static class Reproduce implements Runnable {

        @CommandLine.Option(
                names = {"-a", "--api-tokens"},
                paramLabel = "TOKEN-FILE",
                description = "A file containing a newline separated list of GitHub API tokens",
                required = true
        )
        Path apiTokenFile;

        @CommandLine.Option(
                names = {"-u", "--unsuccessful-reproductions-dir"},
                paramLabel = "UNSUCCESSFUL-REPRODUCTIONS-DIR",
                description = "The directory where unsuccessful dependency update reproduction information should be written.",
                required = true
        )
        Path unsuccessfulReproductionsDir;

        @CommandLine.Option(
                names = {"-d", "--in-progress-reproductions-dir"},
                paramLabel = "NOT-YET-REPRODUCED-DATA-DIR",
                description = "The directory where in-progress candidate breaking update files are located.",
                required = true
        )
        Path notYetReproducedDataDir;

        @CommandLine.Option(
                names = {"-l", "--log-dir"},
                paramLabel = "LOG-DIR",
                description = "The directory where maven logs and reproduction information should be written.",
                required = true
        )
        Path logDir;

        @CommandLine.Option(
                names = {"-j", "--jar-dir"},
                paramLabel = "JAR-DIR",
                description = "The directory where jar files for the changed dependencies should be stored.",
                required = true
        )
        Path jarDir;

        @CommandLine.Option(
                names = {"-f", "--file"},
                paramLabel = "BREAKING-UPDATE-FILE",
                description = "A JSON file for a specific breaking update to reproduce. If not provided, " +
                        "all breaking updates in the dataset directory which have not already been reproduced " +
                        "will be reproduced instead."
        )
        Path breakingUpdateFile;

        @CommandLine.Option(
                names = {"-c", "--github-packages-credentials"},
                paramLabel = "GITHUB-PACKAGES-CREDENTIALS",
                description = "A JSON file containing the credentials required to push an image to GitHub packages."
        )
        Path credentialsFile;

        @CommandLine.Option(
                names = {"-w", "--workflow-log-download-dir"},
                paramLabel = "WORKFLOW-DIR",
                description = "The directory to download workflow logs. If a workflow directory is not defined, " +
                        "workflow log files will not be downloaded."
        )
        String workflowDir;

        @CommandLine.Option(
                names = {"-ud", "--user-data-dir"},
                paramLabel = "USER-DATA-DIR",
                description = "The directory where the user data in Chrome is saved. This is required to keep an active " +
                        "web session with GitHub, when downloading the workflow log files. If it is not necessary to download" + 
                        "workflow logs, this option can be ignored."
        )
        String userDataDir;

        @CommandLine.Option(
                names = {"-ch", "--chrome-driver-path"},
                paramLabel = "CHROME-DRIVER-PATH",
                description = "The chrome driver path. This is required to run the WorkflowLogFinder in Windows systems."
        )
        String chromeDriverPath;

        @CommandLine.Option(
                names = {"-np", "--no-push", "--dont-push"},
                paramLabel = "DONT-PUSH",
                description = "Set this flag if you do not want to push to a remote repo"
        )
        boolean noPush;

        @CommandLine.Option(
                names = {"-v", "--volume"},
                paramLabel = "VOLUME-NAME",
                description = "A docker volume that contains the maven packages as to not redownload them constantly"
        )
        String cacheVolume;

        @CommandLine.Option(
                names = {"-di", "--delete-images"},
                paramLabel = "DELETE-IMAGES",
                description = "Set this flag if you want to delete the local images after running (really useful if you are already pushing the images to github)"
        )
        boolean deleteImages;

        @CommandLine.Option(
                names = {"-prl", "--parallel"},
                paramLabel = "PARALLEL",
                description = "Set this flag if you want to reproduce the breaking updates in parallel and with how many threads."
        )
        Integer parallel;

        @CommandLine.Option(
                names = {"-rdd", "--reproduction-data-dir"},
                paramLabel = "REPRODUCTION-DATA-DIR",
                description = "The directory where in-progress candidate breaking update files are located will be moved to" +
                        "If this is not set the files will be deleted after a successful reproduction instead"
        )
        Path reproductionDataDir;

        // 3 new paths -rdb -rdf and -rdn, representing breaking change reproduction, fixing change, and no change
        @CommandLine.Option(
                names = {"-rdb", "--reproduction-breaking-dir"},
                paramLabel = "REPRODUCTION-BREAKING-DIR",
                description = "The directory where the reproducible breaking dependency update files are stored",
                required = true
        )
        Path reproductionBreakingDir;

        @CommandLine.Option(
                names = {"-rdf", "--reproduction-fixing-dir"},
                paramLabel = "REPRODUCTION-FIXING-DIR",
                description = "The directory where the reproducible fixing dependency update files are stored",
                required = true
        )
        Path reproductionFixingDir;

        @CommandLine.Option(
                names = {"-rdn", "--reproduction-no-change-dir"},
                paramLabel = "REPRODUCTION-NO-CHANGE-DIR",
                description = "The directory where the reproducible non breaking non fixing dependency update files are stored",
                required = true
        )
        Path reproductionNoChangeDir;

        // always failing path, if not set they won't be stored and wont be made into images
        @CommandLine.Option(
                names = {"-rdaf", "--reproduction-always-fail-dir"},
                paramLabel = "REPRODUCTION-ALWAYS-FAIL-DIR",
                description = "The directory where the always failing dependency update files are stored, if this is not set they won't be stored (nor their images)"
        )
        Path reproductionAlwaysFailDir;

        @Override
        public void run() {
            try {
                if(!noPush && credentialsFile == null) {
                    throw new IllegalArgumentException("GitHub packages credentials file must be provided if pushing to GitHub packages is enabled.");
                }

                DependencyUpdateReproducer reproducer = getReproducer();

                if (breakingUpdateFile != null) {
                    DependencyUpdate bu = JsonUtils.readFromFile(breakingUpdateFile, DependencyUpdate.class);
                    reproducer.reproduce(bu);
                } else {
                    File[] breakingUpdates = notYetReproducedDataDir.toFile().listFiles();
                    if (breakingUpdates != null && breakingUpdates.length > 0) {
                        reproducer.reproduceAll(breakingUpdates);
                    }
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        private @NonNull DependencyUpdateReproducer getReproducer() throws IOException {
            FailureLogManager failureLogManager = new FailureLogManager(logDir);
            ResultManager resultManager = getResultManager(failureLogManager);

            return new DependencyUpdateReproducer(resultManager, failureLogManager, cacheVolume, parallel);
        }

        private @NonNull ResultManager getResultManager(FailureLogManager failureLogManager) throws IOException {
            List<String> apiTokens = Files.readAllLines(apiTokenFile);
            GitHubAPITokenQueue tokenQueue = new GitHubAPITokenQueue(apiTokens);

            GitHubManager gitHubManager = null;
            if(!noPush) {
                GitHubPackagesCredentials credentials = GitHubPackagesCredentials.fromJson(credentialsFile);
                gitHubManager = new GitHubManager(tokenQueue, credentials);
            }

            WorkflowLogFinder workflowLogFinder = null;
            if(workflowDir != null) {
                workflowLogFinder = new WorkflowLogFinder(tokenQueue, chromeDriverPath, userDataDir, workflowDir);
            }

            DependencyRefLinkFinder dependencyRefLinkFinder = new DependencyRefLinkFinder(tokenQueue);

            return new ResultManager(unsuccessfulReproductionsDir, notYetReproducedDataDir, reproductionDataDir,
                    failureLogManager, jarDir, gitHubManager, deleteImages, workflowLogFinder, dependencyRefLinkFinder,
                    reproductionBreakingDir, reproductionFixingDir, reproductionNoChangeDir, reproductionAlwaysFailDir);
        }
    }
}
