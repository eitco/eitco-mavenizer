package de.eitco.mavenizer.analyze;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.eitco.mavenizer.MavenRemoteService;
import de.eitco.mavenizer.MavenUid;
import de.eitco.mavenizer.MavenUid.MavenUidComponent;
import de.eitco.mavenizer.Util;
import de.eitco.mavenizer.MavenRemoteService.OnlineJarResult;
import de.eitco.mavenizer.analyze.Analyzer.JarHashes;

public class OnlineAnalyzer {

	@SuppressWarnings("unused")
	private static final Logger LOG = LoggerFactory.getLogger(OnlineAnalyzer.class);
	
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
	
	public enum OnlineMatch {
		FOUND_MATCH_EXACT_SHA,
		FOUND_MATCH_EXACT_CLASSES_SHA,
		FOUND_MATCH_SUPERSET_CLASSNAMES,
		FOUND_NO_MATCH,
		NOT_FOUND;
		
		public boolean isConsideredIdentical() {
			return this.equals(OnlineMatch.FOUND_MATCH_EXACT_SHA)
					|| this.equals(OnlineMatch.FOUND_MATCH_EXACT_CLASSES_SHA);
		}
	}
	
	private static final int ONLINE_SEARCH_THRESHOLD = 1;// minimum score a candidate value must have to be considered for online search
	
	private final Map<MavenUidComponent, Integer> candidatesToCheckConfig = Map.of(
			MavenUidComponent.GROUP_ID, 2,
			MavenUidComponent.ARTIFACT_ID, 2,
			MavenUidComponent.VERSION, 2
			);
	
	private final MavenRemoteService mavenRemotes;
	
	public OnlineAnalyzer(Optional<List<String>> customRemoteRepos) {
		mavenRemotes = new MavenRemoteService(customRemoteRepos);
	}
	
	List<String> getRemoteRepos() {
		return mavenRemotes.getRemoteRepos();
	}
	
	public void shutdown() {
		mavenRemotes.shutdown();
	}

	/**
	 * @return Map of candidate UIDs that should be checked online, with the summed scores of each UID component.
	 *   Can contain UIDs where version is null, if given version's scores did no pass threshold check or if no versions were passed.
	 */
	public Map<MavenUid, Map<MavenUidComponent, Integer>> selectCandidatesToCheck(Map<MavenUidComponent, List<ValueCandidate>> candidatesMap) {
		Predicate<ValueCandidate> scoreCheck = candidate -> candidate.getScoreSum() >= ONLINE_SEARCH_THRESHOLD;
		
		int maxCount = candidatesToCheckConfig.values().stream().reduce(1, (a, b) -> a * b);// multiply config values
		var result = new LinkedHashMap<MavenUid, Map<MavenUidComponent, Integer>>(maxCount);// use linked to make sure highest score combinations are downloaded first
		
		var groupIds = Util.subList(candidatesMap.get(MavenUidComponent.GROUP_ID), candidatesToCheckConfig.get(MavenUidComponent.GROUP_ID), scoreCheck);
		for (var group : groupIds) {
			
			var artifactIds = Util.subList(candidatesMap.get(MavenUidComponent.ARTIFACT_ID), candidatesToCheckConfig.get(MavenUidComponent.ARTIFACT_ID), scoreCheck);
			for (var artifact : artifactIds) {
				
				var versions = Util.subList(candidatesMap.get(MavenUidComponent.VERSION), candidatesToCheckConfig.get(MavenUidComponent.VERSION), scoreCheck);
				if (versions.isEmpty()) {
					var scores = Map.of(
							MavenUidComponent.GROUP_ID, group.getScoreSum(),
							MavenUidComponent.ARTIFACT_ID, artifact.getScoreSum());
					
					result.put(new MavenUid(group.value, artifact.value, null), scores);
				} else {
					for (var version : versions) {
						var scores = Map.of(
								MavenUidComponent.GROUP_ID, group.getScoreSum(),
								MavenUidComponent.ARTIFACT_ID, artifact.getScoreSum(),
								MavenUidComponent.VERSION, version.getScoreSum());
						
						result.put(new MavenUid(group.value, artifact.value, version.value), scores);
					}
				}
			}
		}
		
		return result;
	}
	
	public CompletableFuture<Set<UidCheck>> findJars(JarHashes localHashes, Set<MavenUid> uidCandidates) {
		return CompletableFuture.supplyAsync(() -> {
			
			Set<UidCheck> results = new LinkedHashSet<>();
			for (var uid : uidCandidates) {
				CompletableFuture<Optional<OnlineJarResult>> onlineJarFuture = mavenRemotes.downloadJar(uid, false);
				
				CompletableFuture<UidCheck> checkedFuture = onlineJarFuture.thenApplyAsync(onlineJarResult -> {
					if (onlineJarResult.isPresent()) {
						OnlineJarResult onlineJar = onlineJarResult.get();
						var url = Optional.of(onlineJar.url);
						if (localHashes.jarSha256.equals(onlineJar.hashes.jarSha256)) {
							return new UidCheck(uid, OnlineMatch.FOUND_MATCH_EXACT_SHA, url);
						} else if (classHashesMatch(localHashes, onlineJar.hashes)) {
							return new UidCheck(uid, OnlineMatch.FOUND_MATCH_EXACT_CLASSES_SHA, url);
						} else {
							return new UidCheck(uid, OnlineMatch.FOUND_NO_MATCH, url);
						}
					} else {
						return new UidCheck(uid, OnlineMatch.NOT_FOUND, Optional.empty());
					}
				});
				
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
	
	public CompletableFuture<Map<MavenUid, Set<UidCheck>>> searchVersionsAndFindJars(JarHashes localHashes, Set<MavenUid> uidCandidates) {
		
		return mavenRemotes.fullyInitialized().thenApplyAsync(__ -> {
			var result = new HashMap<MavenUid, Set<UidCheck>>();
			
			for (var uid : uidCandidates) {
				if (uid.groupId == null || uid.artifactId == null || uid.version != null) {
					throw new IllegalArgumentException();
				}
				var versions = mavenRemotes.downloadVersionsBlocking(uid);
				if (!versions.isEmpty()) {
					var selectedVersions = selectVersionCandidates(uid, versions);
					var fullUidResults = Util.run(() -> findJars(localHashes, selectedVersions).get());
					result.put(uid, fullUidResults);
				}
			}
			return result;
		});
	}
	
	private Set<MavenUid> selectVersionCandidates(MavenUid uidWithoutVersion, List<String> versions) {
		if (versions.isEmpty()) {
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
	
	private boolean classHashesMatch(JarHashes localHashes, JarHashes onlineHashes) {
		if (localHashes.classesToSha256.size() != onlineHashes.classesToSha256.size()) {
			return false;
		}
		for (var localClassEntry : localHashes.classesToSha256.entrySet()) {
			var classPath = localClassEntry.getKey();
			var localClassHash = localClassEntry.getValue();
			var onlineClassHash = onlineHashes.classesToSha256.get(classPath);
			if (!Arrays.equals(localClassHash, onlineClassHash)) {
				return false;
			}
		}
		return true;
	}
}
