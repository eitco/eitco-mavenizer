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

import de.eitco.mavenizer.Cli;
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
import de.eitco.mavenizer.analyze.jar.PostAnalyzer;

public class JarAnalyzer {
	
	public enum JarAnalyzerType {
		MANIFEST("Manifest"),
		JAR_FILENAME("Jar-Filename"),
		POM("Pom"),
		CLASS_FILEPATH("Class-Filepath"),
		CLASS_TIMESTAMP("Class-Timestamp"),
		POST("Post-Analysis");
		
		public final String displayName;
		private JarAnalyzerType(String displayName) {
			this.displayName = displayName;
		}
	}
	
	public static class JarEntry {
		public final Path path;// relative to jar root
		public final FileTime timestampCreatedAt;
		public final FileTime timestampLastModifiedAt;
		public JarEntry(Path path, FileTime createdAt, FileTime lastModifiedAt) {
			this.path = path;
			this.timestampCreatedAt = createdAt;
			this.timestampLastModifiedAt = lastModifiedAt;
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
	
	public enum PomFileType {
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

	private final Cli cli;

	private final ManifestAnalyzer manifestAnalyzer;
	private final JarFilenameAnalyzer jarNameAnalyzer;
	private final PomAnalyzer pomAnalyzer;
	private final ClassFilepathAnalyzer classAnalyzer;
	private final ClassTimestampAnalyzer timeAnalyzer;
	private final PostAnalyzer postAnalyzer;

	public JarAnalyzer(Cli cli) {
		this.cli = cli;
		manifestAnalyzer = new ManifestAnalyzer();
		jarNameAnalyzer = new JarFilenameAnalyzer();
		pomAnalyzer = new PomAnalyzer();
		classAnalyzer = new ClassFilepathAnalyzer(cli);
		timeAnalyzer = new ClassTimestampAnalyzer();
		postAnalyzer = new PostAnalyzer();
	}

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
			
			var candidate = candidates.computeIfAbsent(value, ValueCandidate::new);
			var source = new ValueSource(analyzer, confidenceScore, sourceDetails);
			candidate.addSource(source);
		};
		
		classAnalyzer.analyze(collector.withAnalyzer(classAnalyzer.getType()), classFiles);
		timeAnalyzer.analyze(collector.withAnalyzer(timeAnalyzer.getType()), classFiles);
		pomAnalyzer.analyze(collector.withAnalyzer(pomAnalyzer.getType()), pomFiles);
		manifestAnalyzer.analyze(collector.withAnalyzer(manifestAnalyzer.getType()), manifest.map(m -> m.manifest));
		jarNameAnalyzer.analyze(collector.withAnalyzer(jarNameAnalyzer.getType()), jar.name);
		postAnalyzer.analyze(collector.withAnalyzer(postAnalyzer.getType()), collected); // post analyzer must run last
		
		var sorted = Map.<MavenUidComponent, List<ValueCandidate>>of(
				MavenUidComponent.GROUP_ID, new ArrayList<>(),
				MavenUidComponent.ARTIFACT_ID, new ArrayList<>(),
				MavenUidComponent.VERSION, new ArrayList<>()
				);
		
		var newScoreComparator = Comparator.comparing(ValueCandidate::getScoreSum).reversed();
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
				onClass.accept(new JarEntry(entryPath, entry.getCreationTime(), entry.getLastModifiedTime()));
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
