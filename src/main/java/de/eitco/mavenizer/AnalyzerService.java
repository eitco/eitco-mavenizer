package de.eitco.mavenizer;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.eitco.mavenizer.MavenRepoChecker.CheckResult;
import de.eitco.mavenizer.MavenRepoChecker.UidCheck;
import de.eitco.mavenizer.analyse.ClassFilepathAnalyzer;
import de.eitco.mavenizer.analyse.JarFilenameAnalyzer;
import de.eitco.mavenizer.analyse.ManifestAnalyzer;
import de.eitco.mavenizer.analyse.PomAnalyzer;
import de.eitco.mavenizer.analyse.PomAnalyzer.FileBuffer;
import de.eitco.mavenizer.analyse.PomAnalyzer.PomFileType;

public class AnalyzerService {

	private static final Logger LOG = LoggerFactory.getLogger(AnalyzerService.class);
	
	private static final int MAX_JARS = -1;// -1 to disable limit
	
	private final ManifestAnalyzer manifestAnalyzer = new ManifestAnalyzer();
	private final JarFilenameAnalyzer jarNameAnalyzer = new JarFilenameAnalyzer();
	private final PomAnalyzer pomAnalyzer = new PomAnalyzer();
	private final ClassFilepathAnalyzer classAnalyzer = new ClassFilepathAnalyzer();
	
	private final MavenRepoChecker repoChecker = new MavenRepoChecker();
	
	public void runAnalysis(Path jarsDir) {
		
		System.out.println("Offline-Analysis started.");
		
		List<Path> jarPaths;
		try (Stream<Path> files = Files.list(jarsDir)) {
			jarPaths = files
					.filter(path -> path.getFileName().toString().endsWith(".jar"))
					.collect(Collectors.toList());
	    } catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		var jarCount = jarPaths.size();
		var jarIndex = 0;
		List<JarAnalysisWaitingForCompletion> waiting = new ArrayList<>(jarCount);
		
		// first we do offline analysis an start online analysis for all jars
	    for (var jarPath : jarPaths) {
	    	
	    	if (MAX_JARS >= 0 && jarIndex >= MAX_JARS) {
	    		break;
	    	}
	    	
	    	String jarName = jarPath.getFileName().toString();
	    	String jarHash = MavenUtil.sha256(jarPath.toFile());
	    	Jar jar = new Jar(jarName, jarHash);
	    	
			LOG.debug("Analyzing Jar: '" + jarPath.toString() + "'");
			System.out.print(StringUtil.RETURN_LINE + "Offline-Analysis: Jar " + (jarIndex + 1) + "/" + jarCount);
			
			try (var fin = new FileInputStream(jarPath.toFile())) {
				
				List<FileBuffer> pomFiles = new ArrayList<>(2);
				List<Path> classFilepaths = new ArrayList<>();
				
				var manifest = readJarStream(fin, (entry, in) -> {
					
					var entryPath = Paths.get(entry.getName());
					var filename = entryPath.getFileName().toString();
					
					if (!entry.isDirectory()) {
						if (filename.equals(PomFileType.POM_XML.filename) || filename.equals(PomFileType.POM_PROPS.filename)) {
							try {
								var bytes = in.readAllBytes();
								pomFiles.add(new FileBuffer(entryPath, bytes));
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						}
						if (filename.endsWith(".class")) {
							classFilepaths.add(entryPath);
						}
					}
				});
				
				var collected = Map.<MavenUidComponent, Map<String, ValueCandidate>>of(
						MavenUidComponent.GROUP_ID, new HashMap<>(),
						MavenUidComponent.ARTIFACT_ID, new HashMap<>(),
						MavenUidComponent.VERSION, new HashMap<>()
						);
				
				AnalyzerCandidateCollector collector = (Analyzer analyzer, MavenUidComponent component, String value, int confidenceScore, String sourceDetails) -> {
					Map<String, ValueCandidate> candidates = collected.get(component);
					
					var candidate = candidates.computeIfAbsent(value, key -> new ValueCandidate(key));
					var source = new ValueSource(analyzer, confidenceScore, sourceDetails);
					candidate.addSource(source);
				};
				
				classAnalyzer.analyze(collector.withAnalyzer(classAnalyzer.getType()), classFilepaths);
				pomAnalyzer.analyze(collector.withAnalyzer(pomAnalyzer.getType()), pomFiles);
				manifestAnalyzer.analyze(collector.withAnalyzer(manifestAnalyzer.getType()), manifest);
				jarNameAnalyzer.analyze(collector.withAnalyzer(jarNameAnalyzer.getType()), jarName);
				
				var sorted = Map.<MavenUidComponent, List<ValueCandidate>>of(
						MavenUidComponent.GROUP_ID, new ArrayList<>(),
						MavenUidComponent.ARTIFACT_ID, new ArrayList<>(),
						MavenUidComponent.VERSION, new ArrayList<>()
						);
				
				var newScoreComparator = Comparator.comparing((ValueCandidate candidate) -> candidate.scoreSum).reversed();
				var sourceComparator = Comparator.comparing((ValueSource source) -> source.score).reversed();
				
				for (var uidComponent : MavenUidComponent.values()) {
					var currentCollected = collected.get(uidComponent);
					var currentSorted = sorted.get(uidComponent);
					
					for (var candidate : currentCollected.values()) {
						currentSorted.add(candidate);
						candidate.sortSources(sourceComparator);
					}
					currentSorted.sort(newScoreComparator);
				}
				
//					MavenUid tmp = new MavenUid("test", "foo", null);
//					var checkResults = repoChecker.checkOnline(jarPath, Set.of(tmp));
				
				var toCheck = repoChecker.selectCandidatesToCheck(sorted);
				
				var toCheckWithVersion = toCheck.stream().filter(uid -> uid.version != null).collect(Collectors.toSet());
				var toCheckNoVersion = toCheck.stream().filter(uid -> uid.version == null).collect(Collectors.toSet());
				var checkResultsWithVersion = repoChecker.checkOnline(jarHash, toCheckWithVersion);
				var checkResultsNoVersion = repoChecker.searchVersionsAndcheckOnline(jarHash, toCheckNoVersion);
				
				waiting.add(new JarAnalysisWaitingForCompletion(jar, sorted, checkResultsWithVersion, checkResultsNoVersion));
				
				jarIndex++;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
	    };
	    
		System.out.println();// end System.out.print
		
	    var onlineCheckInitialized = false;
	    System.out.println("Online-Check initializing...");
	    
	    // then wait for each jar to finish online analysis to complete analysis
	    for (var jarAnalysis : waiting) {
	    	var jar = jarAnalysis.jar;
	    	
//	    	boolean groupIdAndArtifactFoundOffline =
//	    			!jarAnalysis.offlineResult.get(MavenUidComponent.GROUP_ID).isEmpty()
//	    			&& !jarAnalysis.offlineResult.get(MavenUidComponent.ARTIFACT_ID).isEmpty();
	    	boolean versionsFoundOffline = !jarAnalysis.offlineResult.get(MavenUidComponent.VERSION).isEmpty();
	    		
	    	var checkResultsWithVersion = MavenUtil.run(() -> jarAnalysis.onlineCompletionWithVersion.get());
	    	var checkResultsNoVersion = MavenUtil.run(() -> jarAnalysis.onlineCompletionNoVersion.get());
	    	
	    	// we expect either only versions being available or only versions missing
	    	if (!checkResultsWithVersion.isEmpty() && !checkResultsNoVersion.isEmpty()) {
	    		throw new IllegalStateException();
	    	}
	    	if (!versionsFoundOffline && !checkResultsWithVersion.isEmpty()) {
	    		throw new IllegalStateException();
	    	}
	    	
	    	// MavenRepoChecker initialization might be finished asynchronously before this point in time,
    		// but we can only start to print after offline analysis is finished to not destroy RETURN_LINE printlns.
	    	if (!onlineCheckInitialized) {
	    		onlineCheckInitialized = true;
	    		System.out.println("Online-Check initialized!");
	    		System.out.println("Online-Check started.");
	    		System.out.println();
	    	}

	    	var padding = 8;
	    	var pad = " ".repeat(padding);
	    	var matchPadding = 16;
	    	var matchPad = " ".repeat(matchPadding);
	    	System.out.println(jar.name);
	    	
	    	Set<UidCheck> onlineResults;
	    	if (versionsFoundOffline) {
	    		onlineResults = checkResultsWithVersion;
	    	} else {
	    		onlineResults = checkResultsNoVersion.values().stream()
	    				.flatMap(Set::stream)
	    				.collect(Collectors.toSet());
	    	}
	    	if (onlineResults.size() == 1) {
	    		var result = onlineResults.iterator().next();
	    		if (result.checkResult.equals(CheckResult.MATCH_EXACT_SHA)) {
	    			System.out.println("    Found identical jar online with uid: " + result.fullUid);
	    			System.out.println("-".repeat(80));
	    			continue;
	    		}
	    	}
	    	
			System.out.println();
			System.out.println("    OFFLINE RESULT");
			printOfflineAnalysisResults(jarAnalysis.offlineResult, padding);
			System.out.println();
			System.out.println("    ONLINE RESULT");
			if (onlineResults.size() >= 1) {
				if (versionsFoundOffline) {
					printOnlineAnalysisResults(onlineResults, padding, matchPadding);
				} else {
					System.out.println(pad + "Found artifactId / groupId pairs online, comparing local jar with random online versions:");
					for (var entry : checkResultsNoVersion.entrySet()) {
						System.out.println(pad + "  " + StringUtil.rightPad("PAIR:", matchPadding + 2) + entry.getKey());
						printOnlineAnalysisResults(entry.getValue(), padding + 4, matchPadding);
					}
				}
			} else {
				if (versionsFoundOffline) {
					System.out.println(pad + "?? 1");
				} else {
					System.out.println(pad + "Did not find any matching artifactId / groupId pair online. Attempt to look for valid versions failed!");
				}
			}
			System.out.println();
			System.out.println("-".repeat(80));
	    }
	    
	    System.out.println("Analysis complete.");
		
		repoChecker.shutdown();
	}
	
	public static void printOnlineAnalysisResults(Set<UidCheck> checkedUids, int padding, int matchPadding) {
		var pad = " ".repeat(padding);
		for (var uidCheck : checkedUids) {
			System.out.println(pad + StringUtil.leftPad(uidCheck.checkResult.name() + " FOR ", matchPadding)  + uidCheck.fullUid);
		}
	}
	
	public static void printOfflineAnalysisResults(Map<MavenUidComponent, List<ValueCandidate>> result, int padding) {
		BiFunction<String, Integer, String> scoredValueToString =
				(value, score) -> StringUtil.leftPad(score + "", 2) + " | " + value;
				
		var pad = " ".repeat(padding);
		for (var uidComponent : MavenUidComponent.values()) {
			
			var resultList = result.get(uidComponent);
			
			if (resultList.size() > 0) {
				System.out.println(pad + uidComponent.name());
			}
			int valuePadding = 20;
			for (ValueCandidate candidate : resultList) {
				var valueString = scoredValueToString.apply(candidate.value, candidate.scoreSum);
				
				int valueLength = valueString.toString().length();
				valuePadding = Math.max(valuePadding, valueLength);
			}
			for (ValueCandidate candidate : resultList) {
				var valueAndScore = scoredValueToString.apply(candidate.value, candidate.scoreSum);
				
				for (int i = 0; i < candidate.sources.size(); i++) {
					var source = candidate.sources.get(i);
					var valueString = pad + "    " + StringUtil.rightPad(i == 0 ? valueAndScore : "", valuePadding + 2);
					var sourceString = " (" + source.score + " | " + source.analyzer.displayName + " -> " + source.details + ")";
					System.out.println(valueString + sourceString);
				}
			}
		}
	}
	
	@FunctionalInterface
	public static interface AnalyzerCandidateCollector {
		void addCandidate(Analyzer analyzer, MavenUidComponent component, String value, int confidenceScore, String sourceDetails);
		
		default ValueCandidateCollector withAnalyzer(Analyzer analyzer) {
			return (component, value, confidenceScore, sourceDetails) -> {
				this.addCandidate(analyzer, component, value, confidenceScore, sourceDetails);
			};
		}
	}
	
	@FunctionalInterface
	public static interface ValueCandidateCollector {
		void addCandidate(MavenUidComponent component, String value, int confidenceScore, String sourceDetails);
	}
	
	public static class Jar {
		public final String name;
		public final String sha256;
		
		public Jar(String name, String sha256) {
			this.name = name;
			this.sha256 = sha256;
		}
	}
	
	public static class JarAnalysisWaitingForCompletion {
		public final Jar jar;
		public final Map<MavenUidComponent, List<ValueCandidate>> offlineResult;
		public final CompletableFuture<Set<UidCheck>> onlineCompletionWithVersion;
		public final CompletableFuture<Map<MavenUid, Set<UidCheck>>> onlineCompletionNoVersion;
		
		public JarAnalysisWaitingForCompletion(Jar jar, Map<MavenUidComponent, List<ValueCandidate>> offlineResult,
				CompletableFuture<Set<UidCheck>> onlineCompletionWithVersion,
				CompletableFuture<Map<MavenUid, Set<UidCheck>>> onlineCompletionNoVersion) {
			this.jar = jar;
			this.offlineResult = offlineResult;
			this.onlineCompletionWithVersion = onlineCompletionWithVersion;
			this.onlineCompletionNoVersion = onlineCompletionNoVersion;
		}
	}
	
	public static enum MavenUidComponent {
		GROUP_ID("groupId"),
		ARTIFACT_ID("artifactId"),
		VERSION("version");
		
		public final String xmlTagName;
		private MavenUidComponent(String xmlTagName) {
			this.xmlTagName = xmlTagName;
		}
	}
	
	public static class MavenUid {
		public final String groupId;
		public final String artifactId;
		public final String version;
		
		public MavenUid(String groupId, String artifactId, String version) {
			this.groupId = groupId;
			this.artifactId = artifactId;
			this.version = version;
		}
		@Override
		public String toString() {
			return "( " + groupId + " | " + artifactId + " | " + (version == null ? "<unknown-version>" : version) + " )";
		}
	}
	
	public static enum Analyzer {
		MANIFEST("Manifest"),
		JAR_FILENAME("Jar-Filename"),
		POM("Pom"),
		CLASS_FILEPATH("Class-Filepath"),
		MAVEN_REPO_CHECK("Repo-Check");
		
		public final String displayName;
		private Analyzer(String displayName) {
			this.displayName = displayName;
		}
	}
	
	public static class ValueCandidate {
		public final String value;
		public final List<ValueSource> sources;
		public int scoreSum = 0;
		
		private final List<ValueSource> sourcesInternal = new ArrayList<>();
		
		public ValueCandidate(String value) {
			this.value = value;
			this.sources = Collections.unmodifiableList(sourcesInternal);
		}
		public void addSource(ValueSource source) {
			sourcesInternal.add(source);
			scoreSum += source.score;
		}
		public void sortSources(Comparator<? super ValueSource> comparator) {
			sourcesInternal.sort(comparator);
		}
	}
	
	public static class ValueSource {
		public final Analyzer analyzer;
		public final int score;
		public final String details;
		
		public ValueSource(Analyzer analyzer, int score, String details) {
			this.analyzer = analyzer;
			this.score = score;
			this.details = details;
		}
	}
	
	public static Optional<Manifest> readJarStream(InputStream in, BiConsumer<JarEntry, InputStream> fileConsumer) {
	    try (var jarIn = new JarInputStream(in, false)) {
	    	var manifest = Optional.ofNullable(jarIn.getManifest());
		    JarEntry entry;
			while ((entry = jarIn.getNextJarEntry()) != null) {
				fileConsumer.accept(entry, jarIn);
				jarIn.closeEntry();
			}
			return manifest;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
//	public static void readZipStream(InputStream in, BiConsumer<ZipEntry, InputStream> fileConsumer) {
//	    try (ZipInputStream zipIn = new ZipInputStream(in)) {
//		    ZipEntry entry;
//			while ((entry = zipIn.getNextEntry()) != null) {
//				fileConsumer.accept(entry, zipIn);
//			    zipIn.closeEntry();
//			}
//		} catch (IOException e) {
//			throw new UncheckedIOException(e);
//		}
//	}
}
