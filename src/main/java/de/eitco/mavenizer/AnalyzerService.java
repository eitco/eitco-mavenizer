package de.eitco.mavenizer;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
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
					
					var classResult = classAnalyzer.analyze(classFilepaths);
					var pomResult = pomAnalyzer.analyze(pomFiles);
					var manifestResult = manifestAnalyzer.analyze(manifest);
					var jarNameResult = jarNameAnalyzer.analyze(jarName);
					
					var result = Map.<MavenUidComponent, List<ValueCandidate>>of(
							MavenUidComponent.GROUP_ID, new ArrayList<ValueCandidate>(),
							MavenUidComponent.ARTIFACT_ID, new ArrayList<ValueCandidate>(),
							MavenUidComponent.VERSION, new ArrayList<ValueCandidate>()
							);
					for (var uidComponent : MavenUidComponent.values()) {
						result.get(uidComponent).addAll(classResult.get(uidComponent));
						result.get(uidComponent).addAll(pomResult.get(uidComponent));
						result.get(uidComponent).addAll(manifestResult.get(uidComponent));
						result.get(uidComponent).addAll(jarNameResult.get(uidComponent));
					}
					
					printAnalysisResults(result);
					
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
		    });
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	public static void printAnalysisResults(Map<MavenUidComponent, List<ValueCandidate>> result) {
		
		var scoreComparator = Comparator.comparing((ValueCandidate candidate) -> candidate.scoredValue.confidence).reversed();
		
		for (var uidComponent : MavenUidComponent.values()) {
			
			var resultList = result.get(uidComponent);
			resultList.sort(scoreComparator);
			
			if (resultList.size() > 0) {
				System.out.println("    " + uidComponent.name());
			}
			int valuePadding = 20;
			for (ValueCandidate candidate : resultList) {
				int valueLength = candidate.scoredValue.toString().length();
				valuePadding = Math.max(valuePadding, valueLength);
			}
			for (ValueCandidate candidate : resultList) {
				System.out.println("        "
						+ StringUtil.rightPad(candidate.scoredValue.toString(), valuePadding + 2)
						+ " (" + candidate.source.displayName + " -> " + candidate.sourceDetails.displaySourceDetails() + ")");
			}
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
	
	public static enum Analyzer {
		MANIFEST("Manifest"),
		JAR_FILENAME("Jar-Filename"),
		POM("Pom"),
		CLASS_FILEPATH("Class-Filepath"),
		MAVEN_REPO_CHEK("Repo-Check");
		
		public final String displayName;
		private Analyzer(String displayName) {
			this.displayName = displayName;
		}
	}
	
	public static class ValueCandidate {
		public ScoredValue scoredValue;
		public Analyzer source;
		public ValueSource sourceDetails;
		
		public ValueCandidate(ScoredValue scoredValue, Analyzer source, ValueSource sourceDetails) {
			this.scoredValue = scoredValue;
			this.source = source;
			this.sourceDetails = sourceDetails;
		}
	}
	
	public static class ScoredValue {
		public final String value;
		public final int confidence;
		public ScoredValue(String value, int confidence) {
			this.value = value;
			this.confidence = confidence;
		}
		@Override
		public String toString() {
			return "[" + confidence + ", " + value + "]";
		}
	}
	
	public static interface ValueSource {
		public String displaySourceDetails();
	}
	
	public static class StringValueSource implements ValueSource {
		private String source;
		public StringValueSource(String source) {
			this.source = source;
		}
		@Override
		public String displaySourceDetails() {
			return source;
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
