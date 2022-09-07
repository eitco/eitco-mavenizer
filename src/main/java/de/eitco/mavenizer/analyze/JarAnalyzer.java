package de.eitco.mavenizer.analyze;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.eitco.mavenizer.MavenUid.MavenUidComponent;
import de.eitco.mavenizer.analyze.Analyzer.Jar;
import de.eitco.mavenizer.analyze.Analyzer.JarAnalysisResult;
import de.eitco.mavenizer.analyze.ValueCandidate.ValueSource;
import de.eitco.mavenizer.analyze.jar.ClassFilepathAnalyzer;
import de.eitco.mavenizer.analyze.jar.ClassTimestampAnalyzer;
import de.eitco.mavenizer.analyze.jar.JarFilenameAnalyzer;
import de.eitco.mavenizer.analyze.jar.ManifestAnalyzer;
import de.eitco.mavenizer.analyze.jar.PomAnalyzer;

public class JarAnalyzer {
	
	public static enum JarAnalyzerType {
		MANIFEST("Manifest"),
		JAR_FILENAME("Jar-Filename"),
		POM("Pom"),
		CLASS_FILEPATH("Class-Filepath"),
		CLASS_TIMESTAMP("Class-Timestamp");
		
		public final String displayName;
		private JarAnalyzerType(String displayName) {
			this.displayName = displayName;
		}
	}
	
	public static class JarEntry {
		public final Path path;// relative to jar root
		public final FileTime timestamp;
		public JarEntry(Path path, FileTime createdAt) {
			this.path = path;
			this.timestamp = createdAt;
		}
	}
	
	public static class ManifestFile {
		public final String fileAsString;
		public final Manifest manifest;
		public ManifestFile(String fileAsString, Manifest manifest) {
			this.fileAsString = fileAsString;
			this.manifest = manifest;
		}
	}
	
	public static class FileBuffer {
		public final Path path;
		public final byte[] content;
		
		public FileBuffer(Path path, byte[] content) {
			this.path = path;
			this.content = content;
		}
	}
	
	public static enum PomFileType {
		POM_XML("pom.xml"),
		POM_PROPS("pom.properties");
		
		public final String filename;
		private PomFileType(String filename) {
			this.filename = filename;
		}
	}
	
	/**
	 * Consumer function that is used by analyzers in {@link de.eitco.mavenizer.analyze.jar} to return any number of value candidates.
	 */
	@FunctionalInterface
	public static interface ValueCandidateCollector {
		void addCandidate(MavenUidComponent component, String value, int confidenceScore, String sourceDetails);
	}
	
	/**
	 * Helper function that extends {@link ValueCandidateCollector} to also provide {@link JarAnalyzerType}.
	 */
	@FunctionalInterface
	private static interface AnalyzerCandidateCollector {
		void addCandidate(JarAnalyzerType analyzer, MavenUidComponent component, String value, int confidenceScore, String sourceDetails);
		
		default ValueCandidateCollector withAnalyzer(JarAnalyzerType analyzer) {
			return (component, value, confidenceScore, sourceDetails) -> {
				this.addCandidate(analyzer, component, value, confidenceScore, sourceDetails);
			};
		}
	}
	
	// Class specific code begins here.
	
	private static final Logger LOG = LoggerFactory.getLogger(JarAnalyzer.class);
	
	private final ManifestAnalyzer manifestAnalyzer = new ManifestAnalyzer();
	private final JarFilenameAnalyzer jarNameAnalyzer = new JarFilenameAnalyzer();
	private final PomAnalyzer pomAnalyzer = new PomAnalyzer();
	private final ClassFilepathAnalyzer classAnalyzer = new ClassFilepathAnalyzer();
	private final ClassTimestampAnalyzer timeAnalyzer = new ClassTimestampAnalyzer();
	
	public JarAnalysisResult analyzeOffline(Jar jar, InputStream compressedJarInput) {
		
		List<FileBuffer> pomFiles = new ArrayList<>(2);
		List<JarEntry> classFiles = new ArrayList<>();
		
		var manifest = readJarStream(compressedJarInput, (entry, in) -> {
			return readJarEntry(entry, in, classFiles::add, pomFiles::add);
		});
		
		if (manifest.isEmpty()) {
			LOG.warn("Did not find manifest in '" + jar.name + "'! Expected 'META-INF/MANIFEST.MF' to exist!");
		}
		
		var collected = Map.<MavenUidComponent, Map<String, ValueCandidate>>of(
				MavenUidComponent.GROUP_ID, new HashMap<>(),
				MavenUidComponent.ARTIFACT_ID, new HashMap<>(),
				MavenUidComponent.VERSION, new HashMap<>()
				);
		
		AnalyzerCandidateCollector collector = (JarAnalyzerType analyzer, MavenUidComponent component, String value, int confidenceScore, String sourceDetails) -> {
			Map<String, ValueCandidate> candidates = collected.get(component);
			
			var candidate = candidates.computeIfAbsent(value, key -> new ValueCandidate(key));
			var source = new ValueSource(analyzer, confidenceScore, sourceDetails);
			candidate.addSource(source);
		};
		
		classAnalyzer.analyze(collector.withAnalyzer(classAnalyzer.getType()), classFiles);
		timeAnalyzer.analyze(collector.withAnalyzer(timeAnalyzer.getType()), classFiles);
		pomAnalyzer.analyze(collector.withAnalyzer(pomAnalyzer.getType()), pomFiles);
		manifestAnalyzer.analyze(collector.withAnalyzer(manifestAnalyzer.getType()), manifest.map(m -> m.manifest));
		jarNameAnalyzer.analyze(collector.withAnalyzer(jarNameAnalyzer.getType()), jar.name);
		
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
		
		return new JarAnalysisResult(manifest, sorted);
	}
	
	private Optional<ManifestFile> readJarEntry(ZipEntry entry, InputStream in, Consumer<JarEntry> onClass, Consumer<FileBuffer> onMavenFile) {
		
		var manifest = Optional.<ManifestFile>empty();
		var entryPath = Paths.get(entry.getName());
		var filenameLower = entryPath.getFileName().toString().toLowerCase();
		
		if (!entry.isDirectory()) {
			if (filenameLower.equals(PomFileType.POM_XML.filename) || filenameLower.equals(PomFileType.POM_PROPS.filename)) {
				try {
					var bytes = in.readAllBytes();
					onMavenFile.accept(new FileBuffer(entryPath, bytes));
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
			if (filenameLower.endsWith(".class")) {
				var timestamp = entry.getCreationTime();
				if (timestamp == null) {
					timestamp = entry.getLastModifiedTime();
				}
				onClass.accept(new JarEntry(entryPath, timestamp));
			}
			if (Paths.get("META-INF/MANIFEST.MF").equals(entryPath)) {
				try {
					byte[] bytes = in.readAllBytes();
					LOG.debug("Parsing manifest.");
					
					var string = new String(bytes, StandardCharsets.UTF_8);
					// JarInputStream is broken and does not always read manifest, so its still possible to find it here even if not just using ZipInputStream
					var parsed = new Manifest(new ByteArrayInputStream(bytes));
					manifest = Optional.of(new ManifestFile(string, parsed));
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
		}
		
		return manifest;
	}
	
	private Optional<ManifestFile> readJarStream(InputStream in, BiFunction<ZipEntry, InputStream, Optional<ManifestFile>> fileConsumer) {
	    try (var jarIn = new ZipInputStream(in)) {
	    	ManifestFile manifest = null;
		    ZipEntry entry;
			while ((entry = jarIn.getNextEntry()) != null) {
				var currentManifest = fileConsumer.apply(entry, jarIn);
				if (manifest == null && currentManifest.isPresent()) {
					manifest = currentManifest.get();
				}
				jarIn.closeEntry();
			}
			return Optional.ofNullable(manifest);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
