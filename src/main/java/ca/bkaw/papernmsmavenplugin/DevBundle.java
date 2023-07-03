package ca.bkaw.papernmsmavenplugin;

/**
 * Configuration for the dev bundle to use.
 */
public class DevBundle {
    /**
     * The default Paper dev bundle.
     */
    public static final DevBundle PAPER_DEV_BUNDLE = new DevBundle(
        "paper-nms",
        new Repository(
            "papermc",
            "https://repo.papermc.io/repository/maven-public/"
        ),
        new Artifact(
            "io.papermc.paper",
            "dev-bundle",
            "${gameVersion}-R0.1-SNAPSHOT"
        )
    );

    public String id;
    public Repository repository;
    public Artifact artifact;

    public DevBundle(String id, Repository repository, Artifact artifact) {
        this.id = id;
        this.repository = repository;
        this.artifact = artifact;
    }

    public DevBundle() {}

    public static class Repository {
        public String id;
        public String url;

        public Repository(String id, String url) {
            this.id = id;
            this.url = url;
        }

        public Repository() {}

    }

    public static class Artifact {
        public String groupId;
        public String artifactId;
        public String version;
        public String classifier;

        public Artifact(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        public Artifact() {}
    }
}
