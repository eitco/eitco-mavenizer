package de.eitco.mavenizer;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import de.eitco.mavenizer.AnalyzerService.MavenUid;
import de.eitco.mavenizer.AnalyzerService.MavenUidComponent;
import de.eitco.mavenizer.AnalyzerService.ValueCandidate;

import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.ArtifactDescriptorReader;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.metadata.DefaultMetadata;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.metadata.Metadata.Nature;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.MetadataRequest;
import org.eclipse.aether.resolution.MetadataResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;

public class MavenRepoChecker {

	Map<MavenUidComponent, Integer> candidatesToCheckConfig = Map.of(
			MavenUidComponent.GROUP_ID, 2,
			MavenUidComponent.ARTIFACT_ID, 2,
			MavenUidComponent.VERSION, 2
			);
	
	public static class Permutation {
		public String groupId;
		public String artifactId;
		public String version;
	}
	
	public static enum CheckResult {
		MATCH_EXACT_SHA,
		MATCH_EXACT_CLASSNAMES,
		MATCH_SUPERSET_CLASSNAMES,
		NO_MATCH;
	}
	
	public Set<MavenUid> selectCandidatesToCheck(Map<MavenUidComponent, List<ValueCandidate>> candidatesMap) {
		return null;
	}
	
	public Map<MavenUid, CompletableFuture<CheckResult>> checkOnline(Set<MavenUid> uidCandidates) {
		return null;
	}
	
	public List<MavenUid> getSortedBlocking(Map<MavenUid, CompletableFuture<CheckResult>> uidCandidates) {
		return null;
	}
	
	public void experiment(Map<MavenUidComponent, List<ValueCandidate>> candidatesMap) {
		
		DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();

        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
		
		RepositorySystem system = locator.getService(RepositorySystem.class);
//		ArtifactDescriptorReader r = locator.getService(ArtifactDescriptorReader.class);
		
		DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
		String localDir = Paths.get(System.getProperty("user.home"), ".m2", "repository").toString();
		String localDir2 = Paths.get("./temp-m2").toString();
	    LocalRepository localRepo = new LocalRepository(localDir2);
	    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
	    
//	    var artifact = new DefaultArtifact("org.apache.camel:camel:pom:3.18.0");
	    var pomArtifact = new DefaultArtifact("org.apache.camel:camel-core:pom:3.18.0");
	    var jarArtifact = new DefaultArtifact("org.apache.camel:camel-core:jar:3.18.0");

	    RemoteRepository mavenCentral = new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build();
	    
	    {
		    Metadata metadata = new DefaultMetadata("org.apache.camel", "camel-core", "maven-metadata.xml", Nature.RELEASE);
		    var request = new MetadataRequest(metadata, mavenCentral, null);
		    
		    List<MetadataResult> results;
			results = system.resolveMetadata(session, List.of(request));
			for (var result : results) {
				Metadata x = result.getMetadata();
				if (!result.isResolved()) {
					throw new IllegalStateException();
				}
				var f = result.getMetadata().getFile();
				try {
					var m = new MetadataXpp3Reader().read(new FileInputStream(f));
					var versions = m.getVersioning().getVersions();
					for (var v : versions) {
						System.out.println(v);
					}
				} catch (IOException | XmlPullParserException e) {
					throw new RuntimeException(e);
				}
				
				System.out.println(result);
				for (var entry : x.getProperties().entrySet()) {
					System.out.println(entry.getKey() + " - " + entry.getValue());
				}
			}
	    }
	    
//	    {
//		    ArtifactDescriptorRequest dReq = new ArtifactDescriptorRequest(pomArtifact, List.of(mavenCentral), null);
//		    ArtifactDescriptorResult dRes;
//			try {
//				dRes = system.readArtifactDescriptor(session, dReq);
//			} catch (ArtifactDescriptorException e) {
//				throw new RuntimeException(e);
//			}
//			System.out.println();
//		    System.out.println(dRes.getArtifact().toString());
//		    System.out.println(dRes.getProperties());
//		    System.out.println(dRes);
//	    }
//	    {
//		    ArtifactDescriptorRequest dReq = new ArtifactDescriptorRequest(jarArtifact, List.of(mavenCentral), null);
//		    ArtifactDescriptorResult dRes;
//			try {
//				dRes = system.readArtifactDescriptor(session, dReq);
//			} catch (ArtifactDescriptorException e) {
//				throw new RuntimeException(e);
//			}
//			System.out.println();
//			System.out.println(dRes.getArtifact().toString());
//		    System.out.println(dRes.getProperties());
//		    System.out.println(dRes);
//	    }
	    
	    {
		    ArtifactRequest request = new ArtifactRequest(pomArtifact, List.of(mavenCentral), null);
		    ArtifactResult result;
			try {
				result = system.resolveArtifact(session, request);
				if (!result.isResolved()) {
					throw new IllegalStateException();
				}
				System.out.println();
				System.out.println(result);
				System.out.println(result.getArtifact().getFile().getName());
			} catch (ArtifactResolutionException e) {
				throw new RuntimeException(e);
			}
	    }
	    
	    throw new IllegalStateException();
	}
}
