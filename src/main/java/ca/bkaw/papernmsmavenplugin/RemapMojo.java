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
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Mojo(name = "remap", defaultPhase = LifecyclePhase.PACKAGE)
public class RemapMojo extends MojoBase {
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path inputPath = this.project.getArtifact().getFile().toPath();

        String gameVersion = this.getGameVersion();
        Path cacheDirectory = this.getCacheDirectory();
        Path mappingsPath = cacheDirectory.resolve("mappings_" + gameVersion + ".tiny");

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

        if (!Files.exists(mappingsPath)) {
            getLog().info("No mappings found, running init");
            this.init();
        }

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

        if (Files.isDirectory(inputPath)) {
            // A directory, we are before the package stage, we need to remap the classes
            getLog().info("Remapping classes");
            this.remapClasses(inputPath, mappingsPath, mappingFrom, mappingTo, classPath);
        } else {
            // A file, we are at the package stage, we need to remap the jar
            Path outputPath = cacheDirectory.resolve("remapped.jar");
            getLog().info("Remapping artifact");
            this.remapArtifact(inputPath, outputPath, mappingsPath, mappingFrom, mappingTo, classPath);
        }
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
                Path path = classesPath.resolve(name + ".class");
                Files.write(path, bytes);
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
}
