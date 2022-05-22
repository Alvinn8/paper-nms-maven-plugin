package ca.bkaw.papernmsmavenplugin;

import net.fabricmc.lorenztiny.TinyMappingFormat;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.artifact.ProjectArtifactMetadata;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.MappingFormats;
import org.cadixdev.lorenz.io.proguard.ProGuardReader;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A base class for all mojos that has shared methods.
 */
public abstract class MojoBase extends AbstractMojo {
    @Parameter( defaultValue = "${project}", required = true, readonly = true )
    MavenProject project;

    @Parameter( defaultValue = "${localRepository}", required = true, readonly = true )
    ArtifactRepository localRepository;

    @Parameter( defaultValue = "${project.remoteArtifactRepositories}" )
    List<ArtifactRepository> remoteRepositories;

    @Component
    ArtifactFactory artifactFactory;

    @Component
    ArtifactInstaller artifactInstaller;

    @Component
    ArtifactResolver artifactResolver;

    // Paths

    /**
     * Get the directory where mapping files are stored and where temporary files are
     * created during init.
     *
     * @return The path to the cache directory.
     */
    public Path getCacheDirectory() {
        MavenProject project = this.project.hasParent() ? this.project.getParent() : this.project;

        return project.getBasedir().toPath().resolve(".paper-nms");
    }

    // Getters

    /**
     * Get the game version the user desires to use for this project.
     *
     * @return The game version.
     * @throws MojoFailureException If no version is found.
     */
    public String getGameVersion() throws MojoFailureException {
        for (Object object : this.project.getDependencies()) {
            Dependency dependency = (Dependency) object;

            if ("ca.bkaw".equals(dependency.getGroupId()) && "paper-nms".equals(dependency.getArtifactId())) {
                String version = dependency.getVersion();
                return version.substring(0, version.indexOf('-'));
            }
        }
        throw new MojoFailureException("Unable to find the version to use.\n" +
            "Unable to find the version to use. Make sure you have the following dependency in your <dependencies> tag:" +
            "\n" +
            "\n<dependency>" +
            "\n    <groupId>ca.bkaw</groupId>" +
            "\n    <artifactId>paper-nms</artifactId>" +
            "\n    <version>1.18.1-SNAPSHOT</version>" +
            "\n    <scope>provided</scope>" +
            "\n</dependency>" +
            "\n" +
            "\n Replacing \"1.18.1\" with the desired version.");
    }

    // Utils

    /**
     * Download a file from a URL.
     *
     * @param url The url to download the file from.
     * @param path The path to place the downloaded file.
     * @throws MojoExecutionException If the download failed.
     */
    public void downloadFile(String url, Path path) throws MojoExecutionException {
        try {
            InputStream in = new URL(url).openStream();
            Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to download " + path.getFileName(), e);
        }
    }

    /**
     * Download a file from a URL and validate the SHA-1 hash to ensure the file
     * downloaded correctly.
     *
     * @param url The url to download the file from.
     * @param path The path to place the downloaded file.
     * @param sha1 The SHA-1 hash.
     * @throws MojoExecutionException If the download failed.
     */
    public void downloadFile(String url, Path path, String sha1) throws MojoExecutionException {
        this.downloadFile(url, path);
        MessageDigest messageDigest = this.getSHA1();
        byte[] hash;
        try {
            hash = messageDigest.digest(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to check hash of downloaded file " + path.getFileName(), e);
        }
        String fileSha1 = this.toHex(hash);
        if (!sha1.equals(fileSha1)) {
            throw new MojoExecutionException("Download failed, sha1 hash of downloaded file did not match. Expected: " + sha1 + " Found: " + fileSha1 + " for file " + path.getFileName());
        }
    }

    /**
     * Convert a byte array to a hexadecimal string.
     *
     * @param bytes The byte array.
     * @return The hex string.
     */
    public String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) {
            result.append(Integer.toString((value & 0xff) + 0x100, 16).substring(1));
        }
        return result.toString();
    }

    /**
     * Get the SHA-1 {@link MessageDigest} and handle errors (that should never occur).
     *
     * @return The SHA-1 {@link MessageDigest} instance.
     * @throws MojoExecutionException If something goes wrong.
     */
    private MessageDigest getSHA1() throws MojoExecutionException {
        // Should never throw as all Java platforms are required to implement SHA-1
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new MojoExecutionException("Unable to find SHA-1 MessageDigest.", e);
        }
    }

    /**
     * Delete the directory on the path recursively, if the directory exists.
     *
     * @param path The path to the directory.
     * @throws IOException If something goes wrong.
     */
    public void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;

        Files.walkFileTree(path, new FileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw exc;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // Init

    /**
     * Initialize paper-nms. Will create a Mojang mapped paper dependency and install
     * it into the local repository.
     *
     * @throws MojoExecutionException If something goes wrong.
     * @throws MojoFailureException If something goes wrong.
     */
    public void init() throws MojoExecutionException, MojoFailureException {
        String gameVersion = this.getGameVersion();
        Path cacheDirectory = this.getCacheDirectory();

        getLog().info("Initializing paper-nms for game version: " + gameVersion);

        getLog().info("Preparing cache folder");
        try {
            Files.createDirectories(cacheDirectory);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to create .paper-nms cache folder.", e);
        }

        Path mappingsPath = cacheDirectory.resolve("mappings_" + gameVersion + ".tiny");
        Path mappedPaperPath = cacheDirectory.resolve("mapped_paper_"+ gameVersion +".jar");
        List<String> dependencyCoordinates = new ArrayList<>();

        getLog().info("Downloading dev-bundle");
        Path devBundlePath = this.resolveDevBundle(gameVersion);

        if (devBundlePath != null) {
            getLog().info("Extracting dev-bundle");
            Path paperclipPath = cacheDirectory.resolve("paperclip.jar");
            this.extractDevBundle(paperclipPath, mappingsPath, devBundlePath, dependencyCoordinates);

            getLog().info("Extracting paper");
            this.extractPaperJar(gameVersion, cacheDirectory, mappedPaperPath);
        } else {
            // No dev-bundle exists for this version, let's create
            // mappings and map the jar manually.

            Path mojangMappingsPath = cacheDirectory.resolve("mojang_mappings.txt");
            this.downloadMojangMappings(mojangMappingsPath, gameVersion);

            getLog().info("Downloading spigot mappings");
            Path spigotClassMappingsPath = cacheDirectory.resolve("spigot_class_mappings_"+ gameVersion +".csrg");
            Path spigotMemberMappingsPath = cacheDirectory.resolve("spigot_member_mappings_"+ gameVersion +".csrg");
            this.downloadSpigotMappings(spigotClassMappingsPath, spigotMemberMappingsPath, gameVersion);

            getLog().info("Merging mappings");
            this.mergeMappings(spigotClassMappingsPath, spigotMemberMappingsPath, mojangMappingsPath, mappingsPath);

            Path paperclipPath = cacheDirectory.resolve("paperclip.jar");
            this.downloadPaper(gameVersion, paperclipPath);

            getLog().info("Extracting paper");
            Path paperPath = cacheDirectory.resolve("paper.jar");
            this.extractPaperJar(gameVersion, cacheDirectory, paperPath);

            getLog().info("Mapping paper jar");
            this.mapPaperJar(mappingsPath, paperPath, mappedPaperPath);
        }

        getLog().info("Installing into local maven repository");
        Path pomPath = cacheDirectory.resolve("pom.xml");
        this.installToMavenRepo(gameVersion, dependencyCoordinates, mappedPaperPath, pomPath);
    }

    /**
     * Resolve the paper dev-bundle containing useful files and get the path to it.
     *
     * <p>For versions that do not have a dev-bundle, null is returned.</p>
     *
     * @param gameVersion The game version of the dev-bundle to download.
     * @return The path of the dev-bundle, or null if no dev-bundle was found.
     */
    @Nullable
    public Path resolveDevBundle(String gameVersion) {
        Artifact artifact = this.artifactFactory.createArtifactWithClassifier(
            "io.papermc.paper",
            "dev-bundle",
            gameVersion + "-R0.1-SNAPSHOT",
            "zip",
            null // classifier
        );

        MavenArtifactRepository paperRepo = new MavenArtifactRepository(
            "papermc",
            "https://repo.papermc.io/repository/maven-public/",
            new DefaultRepositoryLayout(),
            new ArtifactRepositoryPolicy(),
            new ArtifactRepositoryPolicy()
        );

        List<ArtifactRepository> repositories = new ArrayList<>();
        repositories.add(paperRepo);

        try {
            this.artifactResolver.resolve(artifact, repositories, this.localRepository);
        } catch (ArtifactResolutionException | ArtifactNotFoundException e) {
            getLog().info("No dev bundle was found for version " + gameVersion);
            return null;
        }

        return artifact.getFile().toPath();
    }

    /**
     * Extract the needed files from the dev bundle. Will extract the mapped paperclip
     * jar and the mappings.
     *
     * <p>The {@code dependencyCoordinates} list will be populated if the dev bundle
     * is of a data version where the server dependencies are not included in the
     * server jar (1.18+).</p>
     *
     * @param paperclipPath The path to put the mapped paperclip jar.
     * @param mappingsPath The path to put the extracted mappings.
     * @param devBundlePath The path to the dev bundle.
     * @param dependencyCoordinates The mutable list of dependency artifact coordinates.
     * @throws MojoExecutionException If something goes wrong.
     * @throws MojoFailureException If something goes wrong.
     */
    public void extractDevBundle(Path paperclipPath, Path mappingsPath, Path devBundlePath, List<String> dependencyCoordinates) throws MojoExecutionException, MojoFailureException {
        try {
            URI uri = new URI("jar:" + devBundlePath.toUri());
            FileSystem devBundle = FileSystems.newFileSystem(uri, new HashMap<>());

            int dataVersion = Integer.parseInt(String.join("", Files.readAllLines(devBundle.getPath("data-version.txt"))).trim());

            if (dataVersion != 3 && dataVersion != 2) {
                getLog().warn("Unsupported dev-bundle version. Found data version " + dataVersion +
                    " but only 2 and 3 are supported. Things may not work properly. If problems occur, try" +
                    " updating the maven plugin to a newer version if that exists.");
            }

            JSONObject config = new JSONObject(new JSONTokener(Files.newInputStream(devBundle.getPath("config.json"))));
            JSONObject buildData = config.getJSONObject("buildData");

            if (dataVersion >= 3) {
                // Dependencies only need to be added for 1.18+ where they are not in the jar
                for (Object runtimeDependency : buildData.getJSONArray("runtimeDependencies")) {
                    dependencyCoordinates.add(String.valueOf(runtimeDependency));
                }
                // The API is not in the runtimeDependencies array
                dependencyCoordinates.add(config.getString("apiCoordinates"));
                dependencyCoordinates.add(config.getString("mojangApiCoordinates"));
            }

            Path bundleMappingsPath = devBundle.getPath(buildData.getString("reobfMappingsFile"));
            Path bundlePaperclipPath = devBundle.getPath(buildData.getString("mojangMappedPaperclipFile"));

            Files.copy(bundleMappingsPath, mappingsPath, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(bundlePaperclipPath, paperclipPath, StandardCopyOption.REPLACE_EXISTING);

        } catch (URISyntaxException | IOException e) {
            throw new MojoExecutionException("Failed to extract dev-bundle files.", e);
        }
    }

    /**
     * Download the Mojang mappings for the specified game version.
     *
     * @param mojangMappingsPath The path to put the Mojang mappings.
     * @param gameVersion The game version.
     * @throws MojoFailureException If something goes wrong.
     * @throws MojoExecutionException If something goes wrong.
     */
    public void downloadMojangMappings(Path mojangMappingsPath, String gameVersion) throws MojoFailureException, MojoExecutionException {
        try {
            getLog().info("Downloading version manifest");
            InputStream versionManifest = new URL("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json").openStream();
            JSONObject versionManifestJson = new JSONObject(new JSONTokener(versionManifest));
            JSONArray versions = versionManifestJson.getJSONArray("versions");

            String versionInfoUrl = null;
            for (int i = 0; i < versions.length(); i++) {
                JSONObject versionJson = versions.getJSONObject(i);
                String id = versionJson.getString("id");
                if (gameVersion.equals(id)) {
                    versionInfoUrl = versionJson.getString("url");
                    break;
                }
            }
            versionManifest.close();
            if (versionInfoUrl == null) {
                throw new MojoFailureException("The version \"" + gameVersion + "\" was not found.");
            }

            getLog().info("Downloading version info");
            InputStream versionInfo = new URL(versionInfoUrl).openStream();
            JSONObject versionInfoJson = new JSONObject(new JSONTokener(versionInfo));

            JSONObject downloads = versionInfoJson.getJSONObject("downloads");
            JSONObject mappings = downloads.getJSONObject("server_mappings");

            String mappingsUrl = mappings.getString("url");
            String mappingsSha1 = mappings.getString("sha1");
            versionInfo.close();
            getLog().info("Downloading mojang mappings");
            this.downloadFile(mappingsUrl, mojangMappingsPath, mappingsSha1);

        } catch (IOException e) {
            throw new MojoExecutionException("Failed to get the mojang mappings.", e);
        }
    }

    /**
     * Download the Spigot mappings for the specified game version.
     *
     * <p>Note that the member mappings might not exist, and in that case
     * the {@code spigotMemberMappingsPath} file won't exist.</p>
     *
     * @param spigotClassMappingsPath The path to put the class mappings.
     * @param spigotMemberMappingsPath The path to put the member mappings.
     * @param gameVersion The game version.
     * @throws MojoExecutionException If something goes wrong.
     */
    public void downloadSpigotMappings(Path spigotClassMappingsPath, Path spigotMemberMappingsPath, String gameVersion) throws MojoExecutionException {
        getLog().info("Downloading spigot version info");
        InputStream inputStream;
        try {
            inputStream = new URL("https://hub.spigotmc.org/versions/" + gameVersion + ".json").openStream();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to download spigot version info");
        }
        JSONObject json = new JSONObject(new JSONTokener(inputStream));

        JSONObject refs = json.getJSONObject("refs");
        String ref = refs.getString("BuildData");

        String classMappingsUrl = "https://hub.spigotmc.org/stash/projects/SPIGOT/repos/builddata/raw/mappings/bukkit-"+ gameVersion +"-cl.csrg?at=" + ref;
        String memberMappingsUrl = "https://hub.spigotmc.org/stash/projects/SPIGOT/repos/builddata/raw/mappings/bukkit-"+ gameVersion +"-members.csrg?at=" + ref;

        this.downloadFile(classMappingsUrl, spigotClassMappingsPath);
        this.downloadFile(memberMappingsUrl, spigotMemberMappingsPath);

        // New versions don't have member mappings. If no member mappings exist we are
        // redirected to a login page. Detect the html tag or doctype and in that case
        // delete the member mappings file as it is not mappings, but an HTML page.
        try {
            BufferedReader bufferedReader = Files.newBufferedReader(spigotMemberMappingsPath);
            String s = bufferedReader.readLine();
            bufferedReader.close();
            if (s.contains("html")) { // doctype or html tag
                Files.delete(spigotMemberMappingsPath);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to validate spigot member mappings", e);
        }
    }

    /**
     * Merge the spigot mappings and the Mojang mappings to create mappings from
     * Spigot mappings to Mojang mappings, and write the mappings to a file in the
     * tiny format.
     *
     * @param spigotClassMappingsPath The path of the Spigot class mappings (csrg).
     * @param spigotMemberMappingsPath The path of the Spigot member mappings (csrg).
     * @param mojangMappingsPath The path of the mojang mappings (proguard).
     * @param outputPath The path to put the merged mappings (tiny).
     * @throws MojoExecutionException If something goes wrong.
     */
    public void mergeMappings(Path spigotClassMappingsPath, Path spigotMemberMappingsPath, Path mojangMappingsPath, Path outputPath) throws MojoExecutionException {
        MappingSet spigotMappings;
        MappingSet mojangMappings;

        MappingSet spigotClassMappings;
        try {
            spigotClassMappings = MappingFormats.CSRG.read(spigotClassMappingsPath);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read spigot class mappings", e);
        }
        if (Files.exists(spigotMemberMappingsPath)) {
            MappingSet memberMappings;
            try {
                memberMappings = MappingFormats.CSRG.read(spigotMemberMappingsPath);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to read spigot member mappings", e);
            }
            spigotMappings = spigotClassMappings.merge(memberMappings);
        } else {
            spigotMappings = spigotClassMappings;
        }

        try {
            mojangMappings = new ProGuardReader(Files.newBufferedReader(mojangMappingsPath)).read();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read mojang mappings", e);
        }

        MappingSet mappings = spigotMappings.reverse().merge(mojangMappings.reverse());

        this.fixMappings(mappings);

        try {
            TinyMappingFormat.TINY_2.write(mappings, outputPath, "spigot", "mojang");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write merged mappings", e);
        }

        getLog().info("Cleaning up mappings");
        try {
            Files.delete(mojangMappingsPath);
            Files.delete(spigotClassMappingsPath);
            Files.deleteIfExists(spigotMemberMappingsPath);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to clean up mappings");
        }
    }

    /**
     * Fix mapping conflicts and issues that would crash tiny remapper if not fixed.
     * <p>
     * This is quite an ugly fix, and a workaround for a specific version (1.17).
     * Ideally we would want to find a generic way to fix the mappings.
     * <p>
     * In an unlucky case where a plugin uses these methods it could cause issues,
     * but that should hopefully be rare.
     *
     * @param mappings The mappings to fix.
     */
    public void fixMappings(MappingSet mappings) {
        mappings.getClassMapping("net/minecraft/world/level/storage/loot/entries/LootEntryAbstract$Serializer")
            .ifPresent(classMapping -> {
                classMapping.getMethodMapping("serializeType", "(Lcom/google/gson/JsonObject;Lnet/minecraft/world/level/storage/loot/entries/LootEntryAbstract;Lcom/google/gson/JsonSerializationContext;)V")
                    .ifPresent(methodMapping -> {
                        methodMapping.setDeobfuscatedName("mappingfix");
                    });
            });

        mappings.getClassMapping("net/minecraft/world/item/trading/IMerchant")
            .ifPresent(classMapping -> {
                classMapping.getMethodMapping("getWorld", "()Lnet/minecraft/world/level/World;")
                    .ifPresent(methodMapping -> {
                        methodMapping.setDeobfuscatedName("getCommandSenderWorld");
                    });
            });
    }

    /**
     * Download a paperclip jar of the latest build for the specified game version.
     *
     * @param gameVersion The game version.
     * @param paperclipPath The path to put the paperclip path.
     * @throws MojoExecutionException If something goes wrong.
     */
    public void downloadPaper(String gameVersion, Path paperclipPath) throws MojoExecutionException {
        getLog().info("Fetching latest paper build");

        InputStream inputStream;
        try {
            inputStream = new URL("https://api.papermc.io/v2/projects/paper/versions/" + gameVersion).openStream();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to download paper builds", e);
        }
        JSONObject json = new JSONObject(new JSONTokener(inputStream));
        JSONArray builds = json.getJSONArray("builds");
        int highestBuild = -1;
        for (int i = 0; i < builds.length(); i++) {
            int build = builds.getInt(i);
            if (build > highestBuild) {
                highestBuild = build;
            }
        }

        getLog().info("The latest paper build for " + gameVersion + " is " + highestBuild);

        getLog().info("Downloading paper");
        this.downloadFile("https://api.papermc.io/v2/projects/paper/versions/" + gameVersion + "/builds/" + highestBuild + "/downloads/paper-" + gameVersion + "-" + highestBuild + ".jar", paperclipPath);
    }

    /**
     * Extract the paper jar from the downloaded paperclip jar. Depending on the
     * version, the extracted paper jar might have all dependencies shaded or not.
     *
     * <p>If the dependencies are not shaded, a list of dependencies can then be found
     * inside the META-INF/libraries.list file inside the paperclip jar.</p>
     *
     * <p>This method will also clean up the directories that paperclip generate in
     * the cache folder.</p>
     *
     * @param gameVersion The game version.
     * @param cacheDirectory The cache directory.
     * @param paperPath The path to put the extracted paper jar.
     * @throws MojoExecutionException If something goes wrong.
     * @throws MojoFailureException If something goes wrong.
     */
    public void extractPaperJar(String gameVersion, Path cacheDirectory, Path paperPath) throws MojoExecutionException, MojoFailureException {
        String javaExecutable;
        Path bin = Paths.get(System.getProperty("java.home"), "bin");
        Path javaPath = bin.resolve("java");
        if (!Files.exists(javaPath)) {
            javaPath = bin.resolve("java.exe");
        }
        if (Files.exists(javaPath)) {
            javaExecutable = javaPath.toAbsolutePath().toString();
        } else {
            getLog().warn("Unable to find the java executable, will use a generic \"java\".");
            javaExecutable = "java";
        }

        ProcessBuilder processBuilder = new ProcessBuilder(javaExecutable, "-Dpaperclip.patchonly=true", "-jar", "paperclip.jar");
        processBuilder.directory(cacheDirectory.toFile());

        Process process;
        int exitCode;
        try {
            process = processBuilder.start();
            exitCode = process.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new MojoExecutionException("Failed to execute paperclip jar.", e);
        }

        if (exitCode != 0) {
            try {
                BufferedReader buf = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                String line;
                while ((line = buf.readLine()) != null) {
                    getLog().info("[paperclip] " + line);
                    if (line.contains("Java")) {
                        // Probably incorrect Java version. Instead of printing the message
                        // in paperclip which encourages downloading a new java version,
                        // which probably won't help in this case, we show a possible fix.
                        //
                        // The following regex might not be adapted for all paperclip versions,
                        // but an outdated java version is only really an issue on 1.17+
                        Matcher matcher = Pattern.compile("Java (?<number>\\d+)").matcher(line);
                        if (matcher.find()) {
                            String number = matcher.group("number");
                            throw new MojoFailureException("Failed to extract paper due to an outdated Java version." +
                                "\nPaperclip failed due to an outdated Java version." +
                                "\n" +
                                "\nTry changing the project's java version by for example adding the following to your pom.xml." +
                                "\n" +
                                "\n<properties>" +
                                "\n  <maven.compiler.source>"+ number +"</maven.compiler.source>" +
                                "\n  <maven.compiler.target>"+ number +"</maven.compiler.target>" +
                                "\n</properties>" +
                                "\n" +
                                "\nOr make sure the project is running Java " + number + " by going to Project Structure." +
                                "\n" +
                                "\nThis is only required for paper-nms:init, the java version can be downgraded again afterwards if desired."
                            );
                        } else {
                            throw new MojoExecutionException("Paperclip failed due to an outdated Java version." +
                                "\nPaperclip failed due to an outdated Java version." +
                                "\nSee the maven log for a more detailed error output." +
                                "\nPaperclip error log: " + line);
                        }
                    }
                }
            } catch (IOException e) {
                getLog().warn("Failed to fetch detailed error information from paperclip.", e);
            }
            throw new MojoExecutionException("Paperclip exited with exit code: " + exitCode);
        }

        Path extractedPaperPath = cacheDirectory.resolve("versions").resolve(gameVersion).resolve("paper-" + gameVersion + ".jar");
        if (!Files.exists(extractedPaperPath)) {
            extractedPaperPath = cacheDirectory.resolve("cache").resolve("patched_" + gameVersion + ".jar");
            if (!Files.exists(extractedPaperPath)) {
                throw new MojoExecutionException("Unable to find the patched paper jar");
            }
        }

        try {
            Files.move(extractedPaperPath, paperPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to move extracted paper jar into .paper-nms folder", e);
        }
        getLog().info("Extracted paper jar");

        getLog().info("Cleaning up paperclip");
        try {
            Files.delete(cacheDirectory.resolve("paperclip.jar"));

            // Folders created by Paperclip
            deleteRecursively(cacheDirectory.resolve("cache"));
            deleteRecursively(cacheDirectory.resolve("versions"));
            deleteRecursively(cacheDirectory.resolve("libraries"));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to clean up paperclip");
        }
    }

    /**
     * Map the input jar to the output jar.
     *
     * @param in The input jar.
     * @param out The output jar.
     * @param mappingFrom The mapping namespace to map from.
     * @param mappingTo The mapping namespace to map to.
     */
    public void mapJar(Path in, Path out, Path mappingsPath, String mappingFrom, String mappingTo, Path... classPath) throws IOException, URISyntaxException {
        // Read the mappings
        IMappingProvider mappings = TinyUtils.createTinyMappingProvider(mappingsPath, mappingFrom, mappingTo);

        // Create the remapper
        TinyRemapper remapper = TinyRemapper.newRemapper()
            .withMappings(mappings)
            .ignoreConflicts(true)
            .build();

        // Add the class path
        remapper.readClassPath(classPath);

        // Add input file
        remapper.readInputs(in);

        // Create output jar
        Files.deleteIfExists(out);
        URI uri = new URI("jar:" + out.toUri());
        Map<String, Object> env = new HashMap<>();
        env.put("create", true);
        FileSystem outputFileSystem = FileSystems.newFileSystem(uri, env);

        // Copy all non-class files and count classes
        JarFile inputJar = new JarFile(in.toFile());
        int classCount = 0;
        Enumeration<JarEntry> iter = inputJar.entries();
        while (iter.hasMoreElements()) {
            JarEntry jarEntry = iter.nextElement();
            if (jarEntry.getName().endsWith(".class")) {
                classCount++;
            } else if (!jarEntry.isDirectory()) {
                // A non-class file
                InputStream stream = inputJar.getInputStream(jarEntry);

                Path path = outputFileSystem.getPath(jarEntry.getName());
                Path parent = path.getParent();
                if (parent != null) Files.createDirectories(parent);

                Files.copy(stream, path);

                stream.close();
            }
        }

        // Write all remapped classes to the output jar

        remapper.apply((name, bytes) -> {
            Path path = outputFileSystem.getPath(name + ".class");
            try {
                Path parent = path.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(path, bytes);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write class " + name + " to jar", e);
            }
        });

        // Finish up tiny-remapper
        remapper.finish();

        // Close the output jar
        outputFileSystem.close();
    }

    /**
     * Map the paper jar to create a Mojang mapped paper jar.
     *
     * @param mappingsPath The path to the mappings.
     * @param paperPath The path to the paper jar.
     * @param mappedPaperPath The path to put the mapped paper jar.
     * @throws MojoExecutionException If something goes wrong.
     */
    public void mapPaperJar(Path mappingsPath, Path paperPath, Path mappedPaperPath) throws MojoExecutionException {
        try {
            mapJar(paperPath, mappedPaperPath, mappingsPath, "spigot", "mojang");
        } catch (IOException | URISyntaxException e) {
            throw new MojoExecutionException("Failed to map paper jar", e);
        } catch (RuntimeException e) {
            if ("Unfixable conflicts".equals(e.getMessage())) {
                throw new MojoExecutionException(e.getMessage() +
                    "\nUnable to map the paper jar due to unfixable mapping conflicts. " +
                    "This is a known issue for non 1.18 versions, sorry!" +
                    "\nSee https://github.com/Alvinn8/paper-nms-maven-plugin/issues/1", e);
            } else {
                throw e;
            }
        }

        getLog().info("Cleaning up paper jar");
        try {
            Files.delete(paperPath);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to delete paper jar", e);
        }
    }

    /**
     * Install the mapped paper jar to the local maven repository.
     *
     * <p>Will install with the artifact id {@code ca.bkaw}, the artifact id
     * {@code paper-nms} and the version {@code gameVersion-SNAPSHOT} where
     * {@code gameVersion} is replaced with the game version.</p>
     *
     * <p>A pom will be generated and installed with the artifact.</p>
     *
     * <p>If the dependencyCoordinates list is provided (not empty) it will be
     * used to populate the dependencies for the pom. If it is not provided no
     * repositories nor dependencies will be added.</p>
     *
     * @param gameVersion The game version.
     * @param dependencyCoordinates A list of coordinates of dependencies.
     * @param mappedPaperPath The path to the mapped paper jar to install.
     * @param pomPath The path to the pom file that will be generated.
     * @throws MojoExecutionException If something goes wrong.
     */
    public void installToMavenRepo(String gameVersion, List<String> dependencyCoordinates, Path mappedPaperPath, Path pomPath) throws MojoExecutionException {
        StringBuilder pom = new StringBuilder()
            .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<project xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\" xmlns=\"http://maven.apache.org/POM/4.0.0\"\n")
            .append("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n")
            .append("  <modelVersion>4.0.0</modelVersion>\n")
            .append("  <groupId>ca.bkaw</groupId>\n")
            .append("  <artifactId>paper-nms</artifactId>\n")
            .append("  <version>").append(gameVersion).append("-SNAPSHOT</version>\n");

        // Add dependencies
        if (!dependencyCoordinates.isEmpty()) {
            pom.append("\n");
            pom.append("<repositories>\n");
            pom.append("<repository>\n");
            pom.append("    <id>papermc</id>\n");
            pom.append("    <url>https://repo.papermc.io/repository/maven-public/</url>\n");
            pom.append("</repository>\n");
            pom.append("<repository>\n");
            pom.append("    <id>minecraft-libraries</id>\n");
            pom.append("    <name>Minecraft Libraries</name>\n");
            pom.append("    <url>https://libraries.minecraft.net</url>\n");
            pom.append("</repository>\n");
            pom.append("<repository>\n");
            pom.append("    <id>fabric</id>\n");
            pom.append("    <url>https://maven.fabricmc.net</url>\n");
            pom.append("</repository>\n");
            pom.append("</repositories>\n");
            pom.append("\n");
            pom.append("  <dependencies>\n");
            for (String dependency : dependencyCoordinates) {
                // groupId:artifactId:version
                String[] coordinates = dependency.split(":");
                String groupId = coordinates[0];
                String artifactId = coordinates[1];
                String version = coordinates[2];

                pom.append("<dependency>\n");
                pom.append("    <groupId>").append(groupId).append("</groupId>\n");
                pom.append("    <artifactId>").append(artifactId).append("</artifactId>\n");
                pom.append("    <version>").append(version).append("</version>\n");
                pom.append("</dependency>\n");
            }
            pom.append("  </dependencies>\n");
        }

        pom.append("</project>\n");

        try {
            Files.write(pomPath, pom.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write pom.xml", e);
        }

        try {
            this.installViaArtifactInstaller(mappedPaperPath, pomPath, gameVersion);
        } catch (ArtifactInstallationException e) {
            throw new MojoExecutionException("Failed to install mapped paper jar to local repository.", e);
        }

        getLog().info("Installed into local repository");

        getLog().info("Cleaning up");
        try {
            Files.delete(mappedPaperPath);
            Files.deleteIfExists(pomPath);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to clean up", e);
        }
    }

    /**
     * Install the mapped paper jar into the local repository by using the
     * {@link ArtifactInstaller}.
     *
     * <p>Will install with the artifact id {@code ca.bkaw}, the artifact id
     * {@code paper-nms} and the version {@code gameVersion-SNAPSHOT} where
     * {@code gameVersion} is replaced with the game version.</p>
     *
     * @param artifactPath The path to the artifact to install.
     * @param pomPath The path to the pom to install with it.
     * @param gameVersion The game version.
     * @throws ArtifactInstallationException If something goes wrong.
     */
    private void installViaArtifactInstaller(Path artifactPath, Path pomPath, String gameVersion) throws ArtifactInstallationException {
        Artifact artifact = this.artifactFactory.createArtifactWithClassifier("ca.bkaw", "paper-nms", gameVersion + "-SNAPSHOT", "jar", null);

        // Add pom
        ProjectArtifactMetadata pomMetadata = new ProjectArtifactMetadata(artifact, pomPath.toFile());
        artifact.addMetadata(pomMetadata);

        this.artifactInstaller.install(artifactPath.toFile(), artifact, this.localRepository);
    }

    /**
     * Install an artifact into the local repository by running the
     * {@code install:install-file} goal using the maven command line.
     *
     * <p>This method is not used but exists as an alternate way to install the mapped
     * paper jar. Not really sure why I kept it.</p>
     *
     * @param artifactPath The path to the artifact to install.
     * @param pomPath The path to the pom file to install with it.
     * @throws ArtifactInstallationException If something goes wrong.
     * @throws IOException If something goes wrong.
     * @throws InterruptedException If something goes wrong.
     */
    private void installViaCmd(Path artifactPath, Path pomPath) throws ArtifactInstallationException, IOException, InterruptedException {
        String mvn = System.getProperty("os.name").toLowerCase().contains("windows") ? "mvn.cmd" : "mvn";
        int exitCode = new ProcessBuilder(mvn, "-version").start().waitFor();
        if (exitCode != 0) {
            throw new ArtifactInstallationException("Maven not found on path. Exited with exit code: " + exitCode);
        }

        int exitCode2 = new ProcessBuilder(mvn, "install:install-file", "-Dfile=" + artifactPath, "-DpomFile=" + pomPath).start().waitFor();
        if (exitCode2 != 0) {
            throw new ArtifactInstallationException("Failed to install. Maven exited with exit code: " + exitCode);
        }
    }

    /**
     * Install the mapped paper jar into the local repository by copying the files to
     * the expected path in the local repository.
     *
     * <p>This method is not used but exists as an alternate way to install the mapped
     * paper jar. Not really sure why I kept it.</p>
     *
     * @param artifactPath The path to the artifact to install.
     * @param pomPath The path to the pom to install with it.
     * @param gameVersion The game version.
     * @throws ArtifactInstallationException If something goes wrong.
     */
    private void installViaCopy(Path artifactPath, Path pomPath, String gameVersion) throws ArtifactInstallationException {
        Path basePath = Paths.get(this.localRepository.getBasedir(), "ca", "bkaw", "paper-nms", gameVersion + "-SNAPSHOT");

        String artifactName = "paper-nms-" + gameVersion + "-SNAPSHOT";
        Path repoArtifactPath = basePath.resolve(artifactName + ".jar");
        Path repoPomPath = basePath.resolve(artifactName + ".pom");

        try {
            Files.copy(artifactPath, repoArtifactPath, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(pomPath, repoPomPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ArtifactInstallationException("Failed to copy files to local repository");
        }
    }
}
