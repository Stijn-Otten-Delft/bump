package reproducer;

import com.fasterxml.jackson.databind.type.MapType;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import miner.JsonUtils;
import miner.ReproducibleDependencyUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static miner.common.DockerConstants.REPOSITORY;

public class ImageMetadataManager {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private final DockerClient client;

    public ImageMetadataManager(DockerClient client) {
        this.client = client;
    }

    /**
     * Store image metadata for successfully created images. Image metadata includes size of the all downloaded
     * dependencies for the project (.m2 folder) and the size of the project after cloning.
     */
    public void storeImageMetadata(ReproducibleDependencyUpdate bu, List<String> tags, List<String> folderPaths) {
        //this is written so badly it's a crime, it will probably be deleted at some point
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
            log.info("Successfully stored the image metadata for the breaking update {} in image_metadata.json file.",
                    bu.postCommit);
        } catch (RuntimeException | IOException e) {
            log.error("Failed to store the image metadata for the breaking update {}.", bu.postCommit, e);
        }
    }

    /**
     * Store image metadata for successfully created images. Image metadata includes size of the all downloaded
     * dependencies for the project (.m2 folder) and the size of the project after cloning.
     */
    public void storeImageMetadata(ReproducibleDependencyUpdate du, String preTag, String postTag) {
        Map<String, String> reproduction_metadata = new HashMap<>();
        List<String> tags = List.of(postTag, preTag);

        for (int tagCount = 0; tagCount < tags.size(); tagCount++) {
            CreateContainerResponse container = client.createContainerCmd(REPOSITORY + ":" + du.postCommit +
                    tags.get(tagCount)).withCmd("/bin/sh", "-c", "tail -f /dev/null").exec();
            client.startContainerCmd(container.getId()).exec();
            // Execute the `du` command inside the container to get the folder size.
            String[] command = {"/bin/sh", "-c", "du -s " + "/" + du.project};
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
                reproduction_metadata.put((tagCount < 1) ? "prevImageProjectFolderSize" : "postImageProjectFolderSize",
                        String.valueOf(commandOutput[0]));

                if(tagCount < 1) du.setPrevImageProjectFolderSize(Integer.parseInt(commandOutput[0]));
                else du.setPostImageProjectFolderSize(Integer.parseInt(commandOutput[0]));

            } catch (InterruptedException e) {
                log.error("Failed to get the folder size of the folder inside the image {} for the " +
                        "breaking update {}.", REPOSITORY + ":" + du.postCommit + tags.get(tagCount), du.postCommit, e);
            }
            client.stopContainerCmd(container.getId()).withTimeout(1).exec();
            client.removeContainerCmd(container.getId()).exec();
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
            imageMetadata.put(du.postCommit, reproduction_metadata);
            JsonUtils.writeToFile(imageMetadataFilePath, imageMetadata);
            log.info("Successfully stored the image metadata for the breaking update {} in image_metadata.json file.",
                    du.postCommit);
        } catch (RuntimeException | IOException e) {
            log.error("Failed to store the image metadata for the breaking update {}.", du.postCommit, e);
        }
    }
}
