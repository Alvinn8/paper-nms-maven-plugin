package ca.bkaw.papernmsmavenplugin;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Mojo(name = "remap", defaultPhase = LifecyclePhase.PACKAGE)
public class RemapMojo extends MojoBase {
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path artifactPath = this.project.getArtifact().getFile().toPath();

        String gameVersion = this.getGameVersion();
        Path cacheDirectory = this.getCacheDirectory();
        Path outputPath = cacheDirectory.resolve("remapped.jar");
        Path mappingsPath = cacheDirectory.resolve("mappings_" + gameVersion + ".tiny");

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
            Files.deleteIfExists(outputPath);
        } catch (IOException e) {
            throw new MojoExecutionException("IOException " + e.getMessage(), e);
        }

        try {
            this.mapJar(artifactPath, outputPath, mappingsPath, "mojang", "spigot", classPath.toArray(new Path[0]));
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
