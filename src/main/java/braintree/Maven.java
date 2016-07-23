package braintree;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;

public class Maven {
  private final RepositorySystem system;
  private final RepositorySystemSession session;
  private final List<RemoteRepository> remoteRepos;

  public Maven() {
    this.system = newRepositorySystem();
    this.session = newRepositorySystemSession(system, "/tmp/local-mvn-repo");
    this.remoteRepos = ImmutableList.of(
        new RemoteRepository.Builder("central", "default", "http://central.maven.org/maven2/")
        .build());
  }

  public Set<DependencyNode> transitiveDependencies(Artifact artifact) {
    CollectRequest collectRequest = new CollectRequest();
    collectRequest.setRoot(new Dependency(artifact, ""));
    collectRequest.setRepositories(this.remoteRepos);

    CollectResult collectResult = null;
    try {
      collectResult = system.collectDependencies(session, collectRequest);
    } catch (DependencyCollectionException e) {
      throw new RuntimeException(e);
    }

    PreorderNodeListGenerator visitor = new PreorderNodeListGenerator();
    collectResult.getRoot().accept(visitor);

    return ImmutableSet.copyOf(
      visitor.getNodes().stream()
        .filter(d -> !d.getDependency().isOptional())
        .collect(Collectors.toList()));
  }

  public String download(Artifact artifact, File outputDirectory) throws IOException {
    String jarName = artifact.getArtifactId() + "." + artifact.getVersion() + ".jar";
    ArtifactRequest artifactRequest = new ArtifactRequest();
    artifactRequest.setArtifact(artifact);
    artifactRequest.setRepositories(this.remoteRepos);
    try {
      ArtifactResult artifactResult = system.resolveArtifact(session, artifactRequest);
      artifact = artifactResult.getArtifact();
    } catch (ArtifactResolutionException e) {
      throw new IOException("Failed to fetch Maven dependency: " + e.getMessage());
    }
    File outputFile = new File(outputDirectory, jarName);
    Files.copy(artifact.getFile(), outputFile);
    return jarName;
  }

  private static RepositorySystem newRepositorySystem() {
    DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
    locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
    locator.addService(TransporterFactory.class, FileTransporterFactory.class);
    locator.addService(TransporterFactory.class, HttpTransporterFactory.class);

    locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
      @Override
      public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
        exception.printStackTrace();
      }
    });

    return locator.getService(RepositorySystem.class);
  }

  public static DefaultRepositorySystemSession newRepositorySystemSession(
      RepositorySystem system, String localRepoRoot) {
    DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

    LocalRepository localRepo = new LocalRepository(localRepoRoot);
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));

    return session;
  }
}
