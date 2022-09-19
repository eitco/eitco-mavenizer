package de.eitco.mavenizer;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.io.xpp3.SettingsXpp3Reader;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.internal.impl.DefaultRepositoryLayoutProvider;
import org.eclipse.aether.internal.impl.DefaultRepositorySystem;
import org.eclipse.aether.internal.impl.Maven2RepositoryLayoutFactory;
import org.eclipse.aether.metadata.DefaultMetadata;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.metadata.Metadata.Nature;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.MetadataRequest;
import org.eclipse.aether.resolution.MetadataResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transfer.NoRepositoryLayoutException;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.eitco.mavenizer.analyze.Analyzer.JarHashes;

public class MavenRemoteService {

	private static final Logger LOG = LoggerFactory.getLogger(MavenRemoteService.class);
	
	public static class OnlineJarResult {
		public final String url;
		public final JarHashes hashes;
		
		public OnlineJarResult(String url, File downloaded, JarHashes hashes) {
			this.url = url;
			this.hashes = hashes;
		}
	}
	
	private final MavenUid onlineRepoTestJar = new MavenUid("junit", "junit", "4.12");
	
	private final Path TEMP_REPO_PATH =  Paths.get("./eitco-mavenizer-temp-m2");
	private final Path TEMP_SETTINGS_FILE = Paths.get("./eitco-mavenizer-temp-effective-settings.xml");
	
	private final DefaultServiceLocator resolverServiceLocator;
	private final DefaultRepositorySystem repoSystem;
	private final DefaultRepositorySystemSession repoSystemSession;
	private final DefaultRepositoryLayoutProvider repoLayoutProvider;
	
	private final LocalRepository localTempRepo;
	private final LocalRepositoryManager localTempRepoManager;
	
	private final boolean isWindows;
	
	private final List<RemoteRepository> remoteRepos = Collections.synchronizedList(new ArrayList<>());
	
	private final CompletableFuture<?> onLocalRepoDeleted;
	private final CompletableFuture<?> onSettingsFileWritten;
	private final CompletableFuture<?> onRemoteReposConfigured;
	private final CompletableFuture<?> onOnlineAccessChecked;
	
	private final Map<MavenUid, CompletableFuture<Optional<OnlineJarResult>>> onlineJarCache = new ConcurrentHashMap<>();
	
	
	public MavenRemoteService(Optional<List<String>> customRemoteRepos) {
		isWindows = Util.isWindows();
		
		resolverServiceLocator = MavenRepositorySystemUtils.newServiceLocator();
		
		resolverServiceLocator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
		resolverServiceLocator.addService(TransporterFactory.class, FileTransporterFactory.class);
		resolverServiceLocator.addService(TransporterFactory.class, HttpTransporterFactory.class);	
		
		repoSystem = new DefaultRepositorySystem();
		repoSystem.initService(resolverServiceLocator);
		
		localTempRepo = new LocalRepository(TEMP_REPO_PATH.toString());
		repoSystemSession = MavenRepositorySystemUtils.newSession();
		localTempRepoManager = repoSystem.newLocalRepositoryManager(repoSystemSession, localTempRepo);
		
		repoSystemSession.setLocalRepositoryManager(localTempRepoManager);
		
		repoLayoutProvider = new DefaultRepositoryLayoutProvider();
		repoLayoutProvider.addRepositoryLayoutFactory(new Maven2RepositoryLayoutFactory());
		
		// just in case: delete temp repo from previous runs and after process exit
		onLocalRepoDeleted = deleteLocalTempRepo();
		Runtime.getRuntime().addShutdownHook(new Thread() {
		      @Override
		      public void run() {
		    	  deleteLocalTempRepo().join();
		      }
		 });
		
		if (!customRemoteRepos.isEmpty()) {
			int counter = 1;
			for (String url : customRemoteRepos.get()) {
				remoteRepos.add(new RemoteRepository.Builder("customRemote" + counter, "default", url).build());
				counter++;
			}
			onSettingsFileWritten = CompletableFuture.<Void>completedFuture(null);
			onRemoteReposConfigured = CompletableFuture.<Void>completedFuture(null);
			onOnlineAccessChecked = onLocalRepoDeleted;// we cannot test downloading a jar since we don't know what the repos contain
		} else {
			// read settings
			onSettingsFileWritten = writeEffectiveSettingsToFile(TEMP_SETTINGS_FILE);
			onRemoteReposConfigured = onSettingsFileWritten.thenAcceptAsync(__ -> {
				readRepoSettings(TEMP_SETTINGS_FILE);
			});
			
			// test online access
			var readyForDownloads = CompletableFuture.allOf(onLocalRepoDeleted, onRemoteReposConfigured);
			onOnlineAccessChecked = readyForDownloads.thenComposeAsync(__ -> assertOnlineReposReachable());
		}
	}
	
	public CompletableFuture<?> fullyInitialized() {
		return onOnlineAccessChecked;
	}
	
	public List<String> getRemoteRepos() {
		onRemoteReposConfigured.join();
		return remoteRepos.stream()
				.map(RemoteRepository::getUrl)
				.collect(Collectors.toList());
	}
	
	public void shutdown() {
		onlineJarCache.clear();
		Util.run(() -> {
			onRemoteReposConfigured.cancel(true);
			onSettingsFileWritten.get(5, TimeUnit.SECONDS);
		});
	}
	
	private CompletableFuture<Void> deleteLocalTempRepo() {
		return CompletableFuture.runAsync(() -> {
			try {
				FileUtils.deleteDirectory(TEMP_REPO_PATH.toFile());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}
	
	private CompletableFuture<?> assertOnlineReposReachable() {
		// TODO we should make a simple HTTP request against each remote URL to make sure they are available.
		// Downloading a known jar should only be done if we know maven central is one of the repos.
		var testCheck = CompletableFuture.supplyAsync(() -> {
			try {
				var testJarResult = downloadJarBlocking(onlineRepoTestJar, true);// throwOnFail guarantees that jar could be downloaded
				LOG.info("Online repositories are reachable!");
				return testJarResult;
			} catch (Exception e) {
				if (e.getClass().equals(RuntimeException.class) || e.getClass().equals(UncheckedIOException.class)) {
					e = (Exception) e.getCause();
				}
				LOG.error("Online repositories are not reachable! Exiting program.", e);
				System.exit(1);
				return null;
			}
		});
		onlineJarCache.put(onlineRepoTestJar, testCheck);
		return testCheck;
	}
	
	private void readRepoSettings(Path settingsFile) {
		LOG.debug("Parsing repos from '" + settingsFile.toString() + "'.");
		
		Settings settings = Util.parse(in -> new SettingsXpp3Reader().read(in), settingsFile.toFile());

		for (var profile : settings.getProfiles()) {
			if (profile.getActivation().isActiveByDefault()) {
				for (var repo : profile.getRepositories()) {
					remoteRepos.add(new RemoteRepository.Builder(repo.getName(), "default", repo.getUrl()).build());
				}
			}
		}
		
		var mavenCentral = new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build();
		remoteRepos.add(mavenCentral);
	}

	private CompletableFuture<Void> writeEffectiveSettingsToFile(Path settingsFile) {
		try {
			var command = new String[] {
			        isWindows ? "mvn.cmd" : "mvn",
			        "help:effective-settings",
			        "-DshowPasswords=true",
			        "-Doutput=\"" + settingsFile.toString() + "\"",
			};
			var processBuilder = new ProcessBuilder(command);
			processBuilder.redirectErrorStream(true);
			var process = processBuilder.start();
			var onExit = process.onExit();
			
			return onExit.thenAcceptAsync(p -> {
				settingsFile.toFile().deleteOnExit();
				try {
					LOG.debug("Trying to read remote repository configuration via 'mvn help:effective-settings'.");
					var exitValue = p.waitFor();
					if (exitValue != 0) {
						throw new UncheckedIOException(new IOException("Failed to execute 'mvn help:effective-settings'! Is 'mvn' or 'mvn.cmd' available on PATH?"));
					} else {
						LOG.debug("Successfully executed 'mvn help:effective-settings' to get remote repository configuration.");
					}
				} catch (InterruptedException e) {}
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} 
	}
	
	public CompletableFuture<Optional<OnlineJarResult>> downloadJar(MavenUid uid, boolean throwOnFail) {
		// Synchronize to make sure that we never have two parallel remote searches/downloads of the same UID.
		// If we had, the second search might hit local temp repo instead of remote repo, causing downloadJarBlocking to fail to extract the remote URL.
		synchronized (onlineJarCache) {
			return onlineJarCache.computeIfAbsent(uid, key -> {
				return fullyInitialized().thenApplyAsync(__ -> {
					return downloadJarBlocking(uid, false);
				});
			});
		}
	}
		
	private Optional<OnlineJarResult> downloadJarBlocking(MavenUid uid, boolean throwOnFail) {
		var artifact = new DefaultArtifact(uid.groupId, uid.artifactId, "jar", uid.version);
		var request = new ArtifactRequest(artifact, remoteRepos, null);
	    ArtifactResult response;
		try {
			response = repoSystem.resolveArtifact(repoSystemSession, request);
			if (response.isResolved()) {
				LOG.debug("Sucess! Jar found for " + uid + " in repo: " + response.getRepository());
				try {
					String url;
					var repo = response.getRepository();
					if (repo instanceof RemoteRepository) {
						var remote = (RemoteRepository) response.getRepository();
						var layout = repoLayoutProvider.newRepositoryLayout(repoSystemSession, remote);
						url = remote.getUrl() + layout.getLocation(artifact, false).toString();
					} else {
						throw new IllegalStateException("Jar '" + uid + "' was retrieved from local reporitory, but lookup should have been cached instead! Cannot return remote URL.");
					}
					var file = response.getArtifact().getFile();
					return Optional.of(new OnlineJarResult(url, file, Util.sha256(file)));
				} catch (NoRepositoryLayoutException e) {
					throw new RuntimeException();
				}
			} else {
				if (throwOnFail) {
					throw new UncheckedIOException(new IOException("Could not resolve artifact '" + artifact + "' online!"));
				}
			}
		} catch (ArtifactResolutionException e) {
			if (throwOnFail) {
				throw new RuntimeException(e);
			}
		}
		
		LOG.debug("Jar not found for " + uid + ".");
		return Optional.empty();
	}
	
	public List<String> downloadVersionsBlocking(MavenUid uidWithoutVersion) {
		if (!fullyInitialized().isDone()) {
			throw new IllegalStateException("Not initialized!");
		}
		
	    Metadata metadataId = new DefaultMetadata(uidWithoutVersion.groupId, uidWithoutVersion.artifactId, "maven-metadata.xml", Nature.RELEASE);
	    
	    var requestList = new ArrayList<MetadataRequest>(remoteRepos.size());
	    for (var repo : remoteRepos) {
	    	requestList.add(new MetadataRequest(metadataId, repo, null));
	    }
	    List<MetadataResult> responses = repoSystem.resolveMetadata(repoSystemSession, requestList);
		for (var response : responses) {
			if (!response.isResolved()) {
				continue;
			}
			LOG.debug("Sucess! Versions found for " + uidWithoutVersion + " in repo: " + response.getRequest().getRepository());
			var metadataFile = response.getMetadata().getFile();
			var metadata = Util.parse(in -> new MetadataXpp3Reader().read(in), metadataFile);
			var versions = metadata.getVersioning().getVersions();
			return versions;
		}
		LOG.debug("Versions not found for " + uidWithoutVersion + ".");
		return List.of();
	}
}
