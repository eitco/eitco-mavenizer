package de.eitco.mavenizer.analyze;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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

import de.eitco.mavenizer.MavenUid;
import de.eitco.mavenizer.MavenUid.MavenUidComponent;
import de.eitco.mavenizer.Util;

public class MavenRepoChecker {

	private static final Logger LOG = LoggerFactory.getLogger(MavenRepoChecker.class);
	
	private static final int ONLINE_SEARCH_THRESHOLD = 1;// minimum score a candidate value must have to be considered for online search
	
	Map<MavenUidComponent, Integer> candidatesToCheckConfig = Map.of(
			MavenUidComponent.GROUP_ID, 2,
			MavenUidComponent.ARTIFACT_ID, 2,
			MavenUidComponent.VERSION, 2
			);
	
	public static class UidCheck {
		public final MavenUid fullUid;
		public final OnlineMatch matchType;
		public final Optional<String> url;
		
		public UidCheck(MavenUid fullUid, OnlineMatch matchType, Optional<String> url) {
			this.fullUid = fullUid;
			this.matchType = matchType;
			this.url = url;
		}
	}
	
	public static enum OnlineMatch {
		FOUND_MATCH_EXACT_SHA,
		FOUND_MATCH_EXACT_CLASSNAMES,
		FOUND_MATCH_SUPERSET_CLASSNAMES,
		FOUND_NO_MATCH,
		NOT_FOUND;
	}
	
	private static class OnlineJarResult {
		String url;
		File downloaded;
		
		public OnlineJarResult(String url, File downloaded) {
			this.url = url;
			this.downloaded = downloaded;
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
	
	private final Map<MavenUid, CompletableFuture<UidCheck>> uidCheckCache = new ConcurrentHashMap<>();
	
	
	public MavenRepoChecker() {
		isWindows = Util.isWindows();
		
		resolverServiceLocator = MavenRepositorySystemUtils.newServiceLocator();
		
		resolverServiceLocator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
		resolverServiceLocator.addService(TransporterFactory.class, FileTransporterFactory.class);
		resolverServiceLocator.addService(TransporterFactory.class, HttpTransporterFactory.class);	
//		resolverServiceLocator.addService(TransporterFactory.class, WagonTransporterFactory.class);
		
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
		
		// read settings
		onSettingsFileWritten = writeEffectiveSettingsToFile(TEMP_SETTINGS_FILE);
		onRemoteReposConfigured = onSettingsFileWritten.thenAcceptAsync(__ -> {
			readRepoSettings(TEMP_SETTINGS_FILE);
		});
		
		// test online access
		var readyForDownloads = CompletableFuture.allOf(onLocalRepoDeleted, onRemoteReposConfigured);
		onOnlineAccessChecked = readyForDownloads.thenComposeAsync(__ -> assertOnlineReposReachable());
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
	
	public Map<MavenUid, Map<MavenUidComponent, Integer>> selectCandidatesToCheck(Map<MavenUidComponent, List<ValueCandidate>> candidatesMap) {
		Function<ValueCandidate, Boolean> scoreCheck = candidate -> candidate.scoreSum >= ONLINE_SEARCH_THRESHOLD;
		
		int maxCount = candidatesToCheckConfig.values().stream().reduce(1, (a, b) -> a * b);// multiply config values
		var result = new LinkedHashMap<MavenUid, Map<MavenUidComponent, Integer>>(maxCount);// use linked to make sure highest score combinations are downloaded first
		
		var groupIds = Util.subList(candidatesMap.get(MavenUidComponent.GROUP_ID), candidatesToCheckConfig.get(MavenUidComponent.GROUP_ID), scoreCheck);
		for (var group : groupIds) {
			
			var artifactIds = Util.subList(candidatesMap.get(MavenUidComponent.ARTIFACT_ID), candidatesToCheckConfig.get(MavenUidComponent.ARTIFACT_ID), scoreCheck);
			for (var artifact : artifactIds) {
				
				var versions = Util.subList(candidatesMap.get(MavenUidComponent.VERSION), candidatesToCheckConfig.get(MavenUidComponent.VERSION), scoreCheck);
				if (versions.isEmpty()) {
					var scores = Map.of(
							MavenUidComponent.GROUP_ID, group.scoreSum,
							MavenUidComponent.ARTIFACT_ID, artifact.scoreSum);
					
					result.put(new MavenUid(group.value, artifact.value, null), scores);
				} else {
					for (var version : versions) {
						var scores = Map.of(
								MavenUidComponent.GROUP_ID, group.scoreSum,
								MavenUidComponent.ARTIFACT_ID, artifact.scoreSum,
								MavenUidComponent.VERSION, version.scoreSum);
						
						result.put(new MavenUid(group.value, artifact.value, version.value), scores);
					}
				}
			}
		}
		
		return result;
	}
	
	public CompletableFuture<Set<UidCheck>> checkOnline(String localJarHash, Set<MavenUid> uidCandidates) {
		return CompletableFuture.supplyAsync(() -> {
			
			Set<UidCheck> results = new LinkedHashSet<>();
			for (var uid : uidCandidates) {
				CompletableFuture<UidCheck> checkedFuture;
				
				// Use cache to make sure we don't send requests twice for the same uid (which would just result in finding the jar in local repo).
				// If we did not cache the future itself, we would be required to cache the remote URLs due to local jars not knowing where they came from.
				synchronized (uidCheckCache) {
					checkedFuture =  uidCheckCache.get(uid);
					if (checkedFuture == null) {
						checkedFuture = fullyInitialized().thenApplyAsync(__ -> {
							
							var jarResult = downloadJar(uid, false);
							if (jarResult.isPresent()) {
								var onlineJarHash = Util.sha256(jarResult.get().downloaded);
								if (localJarHash.equals(onlineJarHash)) {
									return new UidCheck(uid, OnlineMatch.FOUND_MATCH_EXACT_SHA, Optional.of(jarResult.get().url));
								} else {
									// TODO check classes
									return new UidCheck(uid, OnlineMatch.FOUND_NO_MATCH, Optional.empty());
								}
							} else {
								return new UidCheck(uid, OnlineMatch.NOT_FOUND, Optional.empty());
							}
							
						});
						uidCheckCache.put(uid, checkedFuture);
					}
				}
				
				// we serialize the checks for each call with join here to prevent unnecessary downloads, but multiple calls to this function can still run in parallel
				var check = checkedFuture.join();
				if (check.matchType.equals(OnlineMatch.FOUND_MATCH_EXACT_SHA)) {
					// if we find exact match, we don't continue searching and throw away all previous results
					return Set.of(check);
				} else {
					results.add(check);
				}
			}
			return results;
			
		});
	}
	
	public CompletableFuture<Map<MavenUid, Set<UidCheck>>> searchVersionsAndcheckOnline(String localJarHash, Set<MavenUid> uidCandidates) {
		
		return fullyInitialized().thenApplyAsync(__ -> {
			var result = new HashMap<MavenUid, Set<UidCheck>>();
			
			for (var uid : uidCandidates) {
				if (uid.groupId == null || uid.artifactId == null || uid.version != null) {
					throw new IllegalArgumentException();
				}
				var versions = downloadVersions(uid);
				if (!versions.isEmpty()) {
					var selectedVersions = selectVersionCandidates(uid, versions);
					var fullUidResults = Util.run(() -> checkOnline(localJarHash, selectedVersions).get());
					result.put(uid, fullUidResults);
				}
			}
			return result;
		});
	}
	
	public List<MavenUid> getSortedBlocking(Map<MavenUid, CompletableFuture<OnlineMatch>> uidCandidates) {
		throw new UnsupportedOperationException();
	}
	
	public void shutdown() {
		uidCheckCache.clear();
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
	
	private CompletableFuture<UidCheck> assertOnlineReposReachable() {
		var testCheck = CompletableFuture.supplyAsync(() -> {
			try {
				var checkResult = downloadJar(onlineRepoTestJar, true);// throwOnFail guarantees that jar could be downloaded
				LOG.info("Online repositories are reachable!");
				return new UidCheck(onlineRepoTestJar, OnlineMatch.FOUND_MATCH_EXACT_SHA, Optional.of(checkResult.get().url));
			} catch (Exception e) {
				if (e.getClass().equals(RuntimeException.class) || e.getClass().equals(UncheckedIOException.class)) {
					e = (Exception) e.getCause();
				}
				LOG.error("Online repositories are not reachable! Exiting program.", e);
				System.exit(1);
				return null;
			}
		});
		uidCheckCache.put(onlineRepoTestJar, testCheck);
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
	
	private Optional<OnlineJarResult> downloadJar(MavenUid uid, boolean throwOnFail) {
		
		var artifact = new DefaultArtifact(uid.groupId, uid.artifactId, "jar", uid.version);
		var request = new ArtifactRequest(artifact, remoteRepos, null);
	    ArtifactResult response;
		try {
			response = repoSystem.resolveArtifact(repoSystemSession, request);
			if (response.isResolved()) {
				LOG.debug("Sucess! Jar found for " + uid + " in repo: " + response.getRepository());
				try {
					String url = "";
					var repo = response.getRepository();
					if (repo instanceof RemoteRepository) {
						var remote = (RemoteRepository) response.getRepository();
						var layout = repoLayoutProvider.newRepositoryLayout(repoSystemSession, remote);
						url = remote.getUrl() + layout.getLocation(artifact, false).toString();
					} else {
						throw new IllegalStateException("Jar '" + uid + "' was retrieved from local reporitory, but lookup should have been cached instead! Cannot return remote URL.");
					}
					return Optional.of(new OnlineJarResult(url, response.getArtifact().getFile()));
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
	
	private List<String> downloadVersions(MavenUid uidWithoutVersion) {
		
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
	
	private Set<MavenUid> selectVersionCandidates(MavenUid uidWithoutVersion, List<String> versions) {
		if (versions.size() == 0) {
			throw new IllegalStateException();
		}
		if (versions.size() == 1) {
			return Set.of(new MavenUid(uidWithoutVersion.groupId, uidWithoutVersion.artifactId, versions.get(0)));
		} else {
			// we just select oldest and newest version because there is no good selection strategy anyway, in most cases we hope to have found one or two versions only
			var latestVersion = versions.get(0);
			var oldestVersion = versions.get(versions.size() - 1);
			return new LinkedHashSet<>(List.of(
					new MavenUid(uidWithoutVersion.groupId, uidWithoutVersion.artifactId, latestVersion),
					new MavenUid(uidWithoutVersion.groupId, uidWithoutVersion.artifactId, oldestVersion)
				));
		}
	}
}
