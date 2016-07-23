package braintree;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.DependencyNode;

/**
 * Generate a local copy of all maven artifacts we depend on.
 *
 * Instead of using maven_jar and let bazel download them later, we
 * download jars and put them under third_party/java with java_import
 * as build target. Artifacts with the same groupId will be put into
 * the same directory.
 */
class LocalMirrorGenerator {
  private final String topDir;

  public LocalMirrorGenerator(String topDir) {
    this.topDir = topDir;
  }

  public void generate(Map<Artifact, Set<DependencyNode>> dependencies,
      Set<Artifact> excludeDependencies, Maven maven) {
    ListMultimap<String, DependencyNode> depByGroupId = ArrayListMultimap.create();
    // Group them by Group Id.com.google.common.collect
    dependencies.values().stream()
      .flatMap(Collection::stream)
      .filter(dep -> !excludeDependencies.contains(dep.getArtifact()))
      .forEach(dep -> {
            depByGroupId.put(dep.getArtifact().getGroupId(), dep);
      });

    // Download jar and generate BUILD file by group.
    for (String group : depByGroupId.keySet()) {
      File directory = new File(this.topDir, group);
      File build = new File(directory, "BUILD");
      try {
        Files.createParentDirs(build);
      } catch (IOException e) {
        throw new RuntimeException("Failed to create directory " + directory, e);
      }
      System.out.println("Generate " + build);

      try (PrintStream sw = new PrintStream(build)) {
        sw.println("licenses(['notice'])\n");

        depByGroupId.get(group).stream()
            .sorted(Comparator.comparing(node -> node.getArtifact().getArtifactId()))
            .forEach(node -> createBazelJavaImport(node, sw, directory, maven));
        sw.close();
      } catch (IOException e) {
      }
    }
  }

  private void createBazelJavaImport(DependencyNode node, PrintStream sw,
      File directory, Maven maven) {
    Artifact dep = node.getArtifact();
    String jarName;
    try {
      jarName = maven.download(dep, directory);
    } catch (IOException e) {
      e.printStackTrace();
      return;
    }
    sw.println("java_import(");
    sw.println("  name='" + dep.getArtifactId() + "',");
    sw.println("  visibility = ['//visibility:public'],");
    sw.println("  jars = [");
    sw.println("    '" + jarName + "',");
    sw.println("  ],");
    Set<String> bazelDeps = getBazelDeps(dep, node.getChildren());
    if (!bazelDeps.isEmpty()) {
      sw.println("  deps = [");
      List<String> sorted = Lists.newArrayList(bazelDeps);
      Collections.sort(sorted);
      for (String bazelDep : sorted) {
        sw.println("    '" + bazelDep +"',");
      }
      sw.println("  ],");          
    }
    sw.println(")\n");
  }

  private Set<String> getBazelDeps(Artifact me, List<DependencyNode> deps) {
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    for (DependencyNode dep : deps) {
      if (dep.getDependency().isOptional()) {
        continue;
      }
      String bazelDep;
      // Create a bazel dependency node
      Artifact art = dep.getArtifact();
      if (art.getGroupId().equals(me.getGroupId())) {
        // If we are in the same group, then we are in the same
        // BUILD file, create something like
        // ":org.eclipse.text:jar".
        bazelDep = ":" + art.getArtifactId();
      } else {
        bazelDep = "//" + this.topDir + "/" + art.getGroupId() + ":" + art.getArtifactId();
      }
      builder.add(bazelDep);
    }
    return builder.build();
  }
}
