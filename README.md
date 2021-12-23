# paper-nms-maven-plugin
A maven plugin for using NMS on paper with Mojang mappings.

This plugin will both create the mapped paper dependency and install it to your local repository, and remap your artifact back to spigot mappings.

**Note:** Only works for 1.18.x at the moment, see below.

**Note:** This maven plugin is not on any repository, [see below](#no-maven-repository).

## Usage (IntelliJ)
1. Add `.paper-nms` to your `.gitignore`.
2. Add the plugin to your `pom.xml`:
```xml
<build>
    <plugins>
        ...
        <plugin>
            <groupId>ca.bkaw</groupId>
            <artifactId>paper-nms-maven-plugin</artifactId>
            <version>0.1-SNAPSHOT</version>
            <executions>
                <execution>
                    <phase>package</phase>
                    <goals>
                        <goal>remap</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
        ...
    </plugins>
</build>
```
3. Add the mojang mapped dependency to your `pom.xml`:
```xml
<dependency>
    <groupId>ca.bkaw</groupId>
    <artifactId>paper-nms</artifactId>
    <version>1.18.1-SNAPSHOT</version>
</dependency>
```
4. Reload the project.
![Press the "Load Maven Changes" button](docs/img/step-3.png)
A `Cannot resolve ca.bkaw:paper-nms:1.18.1-SNAPSHOT` message is expected.
5. To create the missing dependency, run `init`.
![Instructions for running the paper-nms:init maven goal](docs/img/step-4.png)
For arrow (4), double-click `paper-nms:init` to run it.
6. Wait for `init` to finish and a `BUILD SUCCESS` message should appear. The `paper-nms` dependency should now exist.
7. Done! Your project should now have a Mojang mapped paper dependency, and when you build you project (for example with `mvn package`) the artifact will be remapped back to spigot mappings.

## Issues
### Only works for 1.18.x.
On 1.17.1 unresolvable mapping conflicts occur, if you want to help to try fix the issue, see issue #1.

On some older spigot versions, mappings use a package rename to avoid having to retype `net/minecraft/server` for every class mapping. See issue #2.

In the future all versions down to 1.14.4 should be supportable as there are Mojang mappings available since then.

### No maven repository
This maven plugin is currently not on any repository. Instead, git clone this repository and run `mvn install` to install the plugin to your local repository.

## Disclaimer

Please bear the licence of the Mojang mappings in mind.

Not affiliated with Minecraft, Mojang or Microsoft.

Not affiliated with Paper or PaperMC.

Not affiliated with Maven or The Apache Software Foundation.
