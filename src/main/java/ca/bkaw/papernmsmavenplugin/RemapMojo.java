package ca.bkaw.papernmsmavenplugin;

import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Mojo(name = "remap", defaultPhase = LifecyclePhase.PACKAGE)
public class RemapMojo extends MojoBase {
    private RemappedClasses remappedClasses;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        this.createDevBundleConfiguration();

        Path inputPath = this.project.getArtifact().getFile().toPath();

        String gameVersion = this.getGameVersion();
        Path cacheDirectory = this.getCacheDirectory();
        Path mappingsPath = cacheDirectory.resolve("mappings_" + gameVersion + ".tiny");

        Path mappingsMojangPath = cacheDirectory.resolve("mappings_" + gameVersion + "_mojang.tiny");
        Path mappingsSpigotPath = cacheDirectory.resolve("mappings_" + gameVersion + "_spigot.tiny");

        if (Files.exists(mappingsMojangPath) != Files.exists(mappingsSpigotPath)) {
            // One of the files is missing, delete the mappings and initialize again
            getLog().info("Broken mappings found, running init");

            try {
                Files.deleteIfExists(mappingsPath);
                Files.deleteIfExists(mappingsMojangPath);
                Files.deleteIfExists(mappingsSpigotPath);
            } catch (IOException exception) {
                throw new MojoExecutionException("Unable to delete mappings", exception);
            }

            this.init();
        }

        String mappingFrom = "mojang";
        String mappingTo = "spigot";

        List<Path> classPath = new ArrayList<>();

        for (Object object : this.project.getDependencies()) {
            Dependency dependency = (Dependency) object;

            Artifact artifact = this.artifactFactory.createArtifactWithClassifier(dependency.getGroupId(), dependency.getArtifactId(),
                dependency.getVersion(), dependency.getType(), dependency.getClassifier());

            try {
                this.artifactResolver.resolve(artifact, this.remoteRepositories, this.localRepository);
            } catch (ArtifactResolutionException | ArtifactNotFoundException e) {
                getLog().error("Failed to resolve "+ artifact.getGroupId() + ":" + artifact.getArtifactId(), e);
                continue;
            }

            classPath.add(artifact.getFile().toPath());
        }

        if (!Files.exists(mappingsPath) && !Files.exists(mappingsMojangPath)) {
            getLog().info("No mappings found, running init");
            this.init();
        }

        boolean hasMojangMappings = Files.exists(mappingsMojangPath);

        if (!hasMojangMappings) {
            try {
                BufferedReader bufferedReader = Files.newBufferedReader(mappingsPath);
                String line = bufferedReader.readLine();
                bufferedReader.close();
                // If the dev bundle is used, there are also yarn parameter mappings
                if (line.contains("mojang+yarn")) {
                    mappingFrom = "mojang+yarn";
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to check the mappings namespace.", e);
            }
        }

        // Tiny remapper is sometimes very verbose about mapping conflicts for older
        // versions where the mappings were created manually by this plugin.
        // We therefore silence the messages. Tiny remapper uses the plain System.out
        // for these messages, so we filter them by changing the System.out.
        PrintStream normalOut = System.out;
        System.setOut(new PrintStream(normalOut) {
            private int count;

            @Override
            public PrintStream printf(String format, Object... args) {
                // Don't print the verbose output
                if (format.contains(" -> ")) {
                    count++;
                    return this;
                }
                return super.printf(format, args);
            }

            @Override
            public void println(String x) {
                // Don't print the verbose output
                if (x.contains("fixable: replaced with")) {
                    count++;
                    return;
                }
                // Replace with the amount of suppressed lines
                if (x.contains("%count%")) {
                    if (count <= 0) {
                        return;
                    }
                    x = x.replace("%count%", String.valueOf(count));
                }
                super.println(x);
            }
        });

        if (hasMojangMappings) {
            remapDouble(inputPath, mappingsMojangPath, mappingsSpigotPath, classPath);
        } else {
            if (Files.isDirectory(inputPath)) {
                // A directory, we are before the package stage, we need to remap the classes
                getLog().info("Remapping classes");
                this.remapClassesCached(inputPath, mappingsPath, mappingFrom, mappingTo, classPath);
            } else {
                // A file, we are at the package stage, we need to remap the jar
                Path outputPath = cacheDirectory.resolve("remapped.jar");
                getLog().info("Remapping artifact");
                this.remapArtifact(inputPath, outputPath, mappingsPath, mappingFrom, mappingTo, classPath);
            }
        }

        System.out.println("/ %count% suppressed lines /");
        System.setOut(normalOut);

        // Save the information about which classes have been remapped so that classes
        // aren't remapped twice if maven chooses to cache classes that weren't changed.
        if (this.remappedClasses != null) {
            try {
                this.remappedClasses.save();
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to save remapped classes json.", e);
            }
        }
    }

    public void remapClassesCached(Path classesPath, Path mappingsPath, String mappingFrom, String mappingTo, List<Path> classPath) throws MojoExecutionException {
        // Read information about which classes have already been remapped
        if (this.remappedClasses == null) {
            try {
                this.remappedClasses = new RemappedClasses(this.getCacheDirectory().resolve(RemappedClasses.FILE_NAME), classesPath);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to read remapped classes json.", e);
            }
        }

        // Read the mappings
        IMappingProvider mappings = TinyUtils.createTinyMappingProvider(mappingsPath, mappingFrom, mappingTo);

        // Create the remapper
        TinyRemapper remapper = TinyRemapper.newRemapper()
            .withMappings(mappings)
            .ignoreConflicts(true)
            .build();

        // Add the class path
        remapper.readClassPath(classPath.toArray(new Path[0]));

        // Add input classes
        remapper.readInputs(classesPath);

        // Run the remapper and write classes
        remapper.apply((name, bytes) -> {
            try {
                Path path = classesPath.resolve(name + ".class");
                if (!this.remappedClasses.isAlreadyRemapped(path)) {
                    Files.write(path, bytes);
                    this.remappedClasses.markAsRemappedNow(path);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write class " + name, e);
            }
        });

        // Finish up tiny-remapper
        remapper.finish();
    }

    public void remapClasses(Path classesPath, Path mappingsPath, String mappingFrom, String mappingTo, List<Path> classPath) {
        // Read the mappings
        IMappingProvider mappings = TinyUtils.createTinyMappingProvider(mappingsPath, mappingFrom, mappingTo);

        // Create the remapper
        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(mappings)
                .ignoreConflicts(true)
                .build();

        // Add the class path
        remapper.readClassPath(classPath.toArray(new Path[0]));

        // Add input classes
        remapper.readInputs(classesPath);

        // Run the remapper and write classes
        remapper.apply((name, bytes) -> {
            try {
                Files.write(classesPath.resolve(name + ".class"), bytes);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write class " + name, e);
            }
        });

        // Finish up tiny-remapper
        remapper.finish();
    }

    public void remapArtifact(Path artifactPath, Path outputPath, Path mappingsPath, String mappingFrom, String mappingTo, List<Path> classPath) throws MojoExecutionException {
        try {
            Files.deleteIfExists(outputPath);
        } catch (IOException e) {
            throw new MojoExecutionException("IOException " + e.getMessage(), e);
        }

        try {
            this.mapJar(artifactPath, outputPath, mappingsPath, mappingFrom, mappingTo, classPath.toArray(new Path[0]));
        } catch (IOException | URISyntaxException e) {
            throw new MojoExecutionException("Failed to remap artifact", e);
        }

        getLog().info("Replacing artifact.");
        try {
            Files.delete(artifactPath);
            Files.move(outputPath, artifactPath);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to replace artifact with the remapped artifact.", e);
        }
    }

    public void remapDouble(Path artifactPath, Path mappingsMojangPath, Path mappingsSpigotPath, List<Path> classPath) throws MojoExecutionException {
        // Map from Mojang to obfuscated
        if (Files.isDirectory(artifactPath)) {
            getLog().info("Remapping classes to obfuscated form");
            this.remapClasses(artifactPath, mappingsMojangPath, "mojang", "obfuscated", classPath);
        } else {
            Path outputPath = getCacheDirectory().resolve("remapped.jar");
            getLog().info("Remapping artifact to obfuscated form");
            this.remapArtifact(artifactPath, outputPath, mappingsMojangPath, "mojang", "obfuscated", classPath);
        }

        getLog().info("Remapping dependencies to obfuscated form");

        List<Path> newClassPath = new ArrayList<>(classPath.size());

        // Keep count to avoid accidentally overwriting dependencies
        int count = 0;

        // Map dependencies from Mojang to obfuscated to gain a correct classpath for the second mapping
        for (Path path : classPath) {
            List<Path> tempClassPath = new ArrayList<>(classPath);
            tempClassPath.remove(path);
            tempClassPath.add(artifactPath);

            Path outputPath = path.getParent().resolve("remapped_dependency_" + count + ".jar");

            try {
                this.mapJar(path, outputPath, mappingsMojangPath, "mojang", "obfuscated", tempClassPath.toArray(new Path[0]));
            } catch (IOException | URISyntaxException exception) {
                throw new MojoExecutionException("Failed to remap dependency", exception);
            }

            newClassPath.add(outputPath);

            count++;
        }

        // Map from obfuscated to Spigot
        if (Files.isDirectory(artifactPath)) {
            getLog().info("Remapping classes to Spigot mappings");
            this.remapClasses(artifactPath, mappingsSpigotPath, "obfuscated", "spigot", newClassPath);
        } else {
            Path outputPath = getCacheDirectory().resolve("remapped_2.jar");
            getLog().info("Remapping artifact to Spigot mappings");
            this.remapArtifact(artifactPath, outputPath, mappingsSpigotPath, "obfuscated", "spigot", newClassPath);
        }

        for (Path path : newClassPath) {
            try {
                Files.delete(path);
            } catch (IOException exception) {
                throw new MojoExecutionException("Unable to delete remapped dependency", exception);
            }
        }
    }
}
