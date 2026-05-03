package miner.common;

import java.io.File;
import java.nio.file.Paths;

/**
 * Common constants used across the miner and reproducer projects.
 */
public final class PathConstants {

    /**
     * The CACHE_DIR where the HTTP caches will be stored is set to the default system
     * temporary directory i.e. /tmp/ on most UNIX-like systems.
     */
    public static final File CACHE_DIR = Paths.get(System.getProperty("java.io.tmpdir")).toFile();

    /** Default file name for the file containing found repositories */
    public static final String FOUND_REPOS_FILE = "found_repositories.json";
}
