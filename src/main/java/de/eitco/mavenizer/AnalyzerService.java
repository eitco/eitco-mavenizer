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
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import de.eitco.mavenizer.analyse.ClassFilepathAnalyzer;
import de.eitco.mavenizer.analyse.JarFilenameAnalyzer;
import de.eitco.mavenizer.analyse.ManifestAnalyzer;
import de.eitco.mavenizer.analyse.PomAnalyzer;
import de.eitco.mavenizer.analyse.PomAnalyzer.FileBuffer;
import de.eitco.mavenizer.analyse.PomAnalyzer.PomFileType;

public class AnalyzerService {

	private final ManifestAnalyzer manifestAnalyzer = new ManifestAnalyzer();
	private final JarFilenameAnalyzer jarNameAnalyzer = new JarFilenameAnalyzer();
	private final PomAnalyzer pomAnalyzer = new PomAnalyzer();
	private final ClassFilepathAnalyzer classAnalyzer = new ClassFilepathAnalyzer();
	
	private final MavenRepoChecker repoChecker = new MavenRepoChecker();
	
	public void runAnalysis(Path jarsDir) {
		
		try (Stream<Path> files = Files.list(jarsDir)) {
		    files.forEach(jarPath -> {
		    	if (!jarPath.getFileName().toString().endsWith(".jar")) {
		    		return;
		    	}
		    	
		    	String jarName = jarPath.getFileName().toString();
				System.out.println(jarName);
				
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
						
//						System.out.println(entry.getName());
//						Path path = Paths.get(entry.getName());
						
//						if (!entry.isDirectory() && path.getFileName().endsWith(".class")) {
//							var classReader = createClassReader(in);
//							
//							System.out.println(classReader.getClassName());
//							Type t = Type.getType(classReader.getClassName());
//							System.out.println("  " + t.getClassName());
//						}
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
					
					System.out.println();
					printAnalysisResults(sorted);
					System.out.println();
					System.out.println("-".repeat(80));
					
					// TODO:
					// get (up to) top 8 MavenUids (permutations), version can be null, values must be above certain score?? 
					// get a CompletableFuture from the MavenChecker with Type Map<MavenUid, CheckResult>
					
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
		    });
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	public static void printAnalysisResults(Map<MavenUidComponent, List<ValueCandidate>> result) {
		BiFunction<String, Integer, String> scoredValueToString =
				(value, score) -> StringUtil.leftPad(score + "", 2) + " | " + value;
		
		for (var uidComponent : MavenUidComponent.values()) {
			
			var resultList = result.get(uidComponent);
			
			if (resultList.size() > 0) {
				System.out.println("    " + uidComponent.name());
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
					var valueString = "        " + StringUtil.rightPad(i == 0 ? valueAndScore : "", valuePadding + 2);
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
	
	public static ClassReader createClassReader(InputStream in) {
		try {
			return new ClassReader(in);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
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
	
	public static class MyClassVisitor extends ClassVisitor {
		protected MyClassVisitor() {
			super(Opcodes.ASM9);
		}
	}
}
