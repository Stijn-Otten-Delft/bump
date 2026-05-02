package reproducer;

import miner.JsonUtils;

import java.nio.file.Path;

public record GitHubPackagesCredentials(String userName, String identityToken) {

    public static GitHubPackagesCredentials fromJson(Path jsonFile) {
        return JsonUtils.readFromFile(jsonFile, GitHubPackagesCredentials.class);
    }

}
