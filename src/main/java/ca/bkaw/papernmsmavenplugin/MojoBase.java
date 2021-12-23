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

    public Path getCacheDirectory() {
        MavenProject project = this.project.hasParent() ? this.project.getParent() : this.project;

        return project.getBasedir().toPath().resolve(".paper-nms");
    }

    // Getters

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
            "\n</dependency>" +
            "\n" +
            "\n Replacing \"1.18.1\" with the desired version.");
    }

    // Utils

    public void downloadFile(String url, Path file) throws MojoExecutionException {
        try {
            InputStream in = new URL(url).openStream();
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to download " + file.getFileName(), e);
        }
    }

    public void downloadFile(String url, Path file, String sha1) throws MojoExecutionException {
        this.downloadFile(url, file);
        MessageDigest messageDigest = this.getSHA1();
        byte[] hash;
        try {
            hash = messageDigest.digest(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to check hash of downloaded file " + file.getFileName(), e);
        }
        String fileSha1 = this.toHex(hash);
        if (!sha1.equals(fileSha1)) {
            throw new MojoExecutionException("Download failed, sha1 hash of downloaded file did not match. Expected: " + sha1 + " Found: " + fileSha1 + " for file " + file.getFileName());
        }
    }

    public String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) {
            result.append(Integer.toString((value & 0xff) + 0x100, 16).substring(1));
        }
        return result.toString();
    }

    private MessageDigest getSHA1() throws MojoExecutionException {
        // Should never throw as all Java platforms are required to implement SHA-1
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new MojoExecutionException("Unable to find SHA-1 MessageDigest.", e);
        }
    }

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

        Path mojangMappingsPath = cacheDirectory.resolve("mojang_mappings.txt");
        this.downloadMojangMappings(mojangMappingsPath, gameVersion);

        getLog().info("Downloading spigot mappings");
        Path spigotClassMappingsPath = cacheDirectory.resolve("spigot_class_mappings_"+ gameVersion +".csrg");
        Path spigotMemberMappingsPath = cacheDirectory.resolve("spigot_member_mappings_"+ gameVersion +".csrg");
        this.downloadSpigotMappings(spigotClassMappingsPath, spigotMemberMappingsPath, gameVersion);

        getLog().info("Merging mappings");
        Path mappingsPath = cacheDirectory.resolve("mappings_" + gameVersion + ".tiny");
        this.mergeMappings(spigotClassMappingsPath, spigotMemberMappingsPath, mojangMappingsPath, mappingsPath);

        Path paperclipPath = cacheDirectory.resolve("paperclip.jar");
        this.downloadPaper(gameVersion, paperclipPath);

        getLog().info("Extracting paper");
        Path paperPath = cacheDirectory.resolve("paper.jar");
        this.extractPaperJar(gameVersion, cacheDirectory, paperPath);

        getLog().info("Mapping paper jar");
        Path mappedPaperPath = cacheDirectory.resolve("mapped_paper_"+ gameVersion +".jar");
        this.mapPaperJar(mappingsPath, paperPath, mappedPaperPath);

        getLog().info("Installing into local maven repository");
        Path pomPath = cacheDirectory.resolve("pom.xml");
        this.installToMavenRepo(gameVersion, cacheDirectory, paperclipPath, mappedPaperPath, pomPath);
    }

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
        // redirected to a login page.
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

    public void downloadPaper(String gameVersion, Path paperclipPath) throws MojoExecutionException {
        getLog().info("Fetching latest paper version");

        InputStream input;
        try {
            input = new URL("https://papermc.io/api/v2/projects/paper/versions/" + gameVersion).openStream();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to download paper builds", e);
        }
        JSONObject json = new JSONObject(new JSONTokener(input));
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
        this.downloadFile("https://papermc.io/api/v2/projects/paper/versions/" + gameVersion + "/builds/" + highestBuild + "/downloads/paper-" + gameVersion + "-" + highestBuild + ".jar", paperclipPath);
    }

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
                                "\nOr make sure the project is running Java " + number + " by going to Project Structure.");
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
            // We must keep the paperclip.jar so that libraries can be read from it
            // when creating a pom

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

    public void mapPaperJar(Path mappingsPath, Path paperPath, Path mappedPaperPath) throws MojoExecutionException {
        try {
            mapJar(paperPath, mappedPaperPath, mappingsPath, "spigot", "mojang");
        } catch (IOException | URISyntaxException e) {
            throw new MojoExecutionException("Failed to map paper jar", e);
        } catch (RuntimeException e) {
            if ("Unfixable conflicts".equals(e.getMessage())) {
                throw new MojoExecutionException(e.getMessage() +
                    "\nUnable to map the paper jar due to unfixable mapping conflicts. " +
                    "This is a known issue for non 1.18 versions, sorry!");
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

    public void installToMavenRepo(String gameVersion, Path cacheDirectory, Path paperclipPath, Path mappedPaperPath, Path pomPath) throws MojoExecutionException {
        StringBuilder pom = new StringBuilder()
            .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<project xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\" xmlns=\"http://maven.apache.org/POM/4.0.0\"\n")
            .append("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n")
            .append("  <modelVersion>4.0.0</modelVersion>\n")
            .append("  <groupId>ca.bkaw</groupId>\n")
            .append("  <artifactId>paper-nms</artifactId>\n")
            .append("  <version>").append(gameVersion).append("-SNAPSHOT</version>\n");

        // Find dependencies
        try {
            FileSystem fileSystem = FileSystems.newFileSystem(paperclipPath, (ClassLoader) null);
            Path librariesPath = fileSystem.getPath("META-INF", "libraries.list");
            if (Files.exists(librariesPath)) {
                pom.append("\n");
                pom.append("<repositories>\n");
                pom.append("<repository>\n");
                pom.append("    <id>papermc</id>\n");
                pom.append("    <url>https://papermc.io/repo/repository/maven-public/</url>\n");
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
                for (String line : Files.readAllLines(librariesPath)) {
                    // hash    groupId:artifactId:version    path
                    String[] parts1 = line.split("\t");
                    String[] parts2 = parts1[1].split(":");
                    String groupId = parts2[0];
                    String artifactId = parts2[1];
                    String version = parts2[2];

                    pom.append("<dependency>\n");
                    pom.append("    <groupId>").append(groupId).append("</groupId>\n");
                    pom.append("    <artifactId>").append(artifactId).append("</artifactId>\n");
                    pom.append("    <version>").append(version).append("</version>\n");
                    pom.append("</dependency>\n");
                }
                pom.append("  </dependencies>\n");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read paperclip jar to find dependencies", e);
        }

        try {
            Files.delete(paperclipPath);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to delete paperclip");
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

    private void installViaArtifactInstaller(Path artifactPath, Path pomPath, String gameVersion) throws ArtifactInstallationException {
        Artifact artifact = this.artifactFactory.createArtifactWithClassifier("ca.bkaw", "paper-nms", gameVersion + "-SNAPSHOT", "jar", null);

        // Add pom
        ProjectArtifactMetadata pomMetadata = new ProjectArtifactMetadata(artifact, pomPath.toFile());
        artifact.addMetadata(pomMetadata);

        this.artifactInstaller.install(artifactPath.toFile(), artifact, this.localRepository);
    }

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

    private void installViaCopy(Path artifactPath, Path pomPath, String gameVersion) throws ArtifactInstallationException {
        Path basePath = Paths.get(this.localRepository.getBasedir(), "ca", "bkaw", "paper-nms", gameVersion + "-SNAPSHOT");

        Path repoArtifactPath = basePath.resolve("paper-nms-" + gameVersion + "-SNAPSHOT.jar");
        Path repoPomPath = basePath.resolve("paper-nms-" + gameVersion + "-SNAPSHOT.pom");

        try {
            Files.copy(artifactPath, repoArtifactPath, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(pomPath, repoPomPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ArtifactInstallationException("Failed to copy files to local repository");
        }
    }
}
