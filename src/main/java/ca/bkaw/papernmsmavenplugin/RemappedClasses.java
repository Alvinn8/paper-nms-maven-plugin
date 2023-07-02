package ca.bkaw.papernmsmavenplugin;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * An object that keeps track of the class files that have already been remapped.
 */
public class RemappedClasses {
    public static final String FILE_NAME = "classes.json";

    private final Path jsonFile;
    private final Path classesFolder;
    private final Map<Path, Long> lastModifiedTimes = new HashMap<>();

    /**
     * Read the data.
     *
     * @param jsonFile The json file containing the data.
     * @param classesFolder The path of the target/classes folder.
     * @throws IOException If the file can not be read.
     */
    public RemappedClasses(Path jsonFile, Path classesFolder) throws IOException {
        this.jsonFile = jsonFile;
        this.classesFolder = classesFolder;
        if (Files.exists(jsonFile)) {
            JSONObject config = new JSONObject(new JSONTokener(Files.newInputStream(jsonFile)));
            for (String key : config.keySet()) {
                Path path = classesFolder.resolve(key);
                long lastModifiedTime = config.getLong(key);
                this.lastModifiedTimes.put(path, lastModifiedTime);
            }
        }
    }

    private long getLastModifiedTime(Path path) throws IOException {
        return Files.getLastModifiedTime(path).toMillis();
    }

    /**
     * Check if the specified class file has already been remapped.
     *
     * @param classFilePath The class file.
     * @return Whether already remapped.
     * @throws IOException If the last modified time failed to be read.
     */
    public boolean isAlreadyRemapped(Path classFilePath) throws IOException {
        Long lastRemapTime = this.lastModifiedTimes.get(classFilePath);
        if (lastRemapTime == null) {
            return false;
        }
        long lastModified = this.getLastModifiedTime(classFilePath);
        return lastModified == lastRemapTime;
    }

    /**
     * Mark a class as having been remapped right now.
     *
     * @param classFilePath The path of the class file that was remapped.
     * @throws IOException If the last modified time fails to be read.
     */
    public void markAsRemappedNow(Path classFilePath) throws IOException {
        this.lastModifiedTimes.put(classFilePath, this.getLastModifiedTime(classFilePath));
    }

    /**
     * Save the data.
     *
     * @throws IOException If the data could not be written.
     */
    public void save() throws IOException {
        JSONObject json = new JSONObject();
        for (Map.Entry<Path, Long> entry : this.lastModifiedTimes.entrySet()) {
            Path path = entry.getKey();
            Path relativePath = this.classesFolder.relativize(path);
            json.put(relativePath.toString(), entry.getValue().longValue());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(this.jsonFile)) {
            json.write(writer);
        }
    }
}
