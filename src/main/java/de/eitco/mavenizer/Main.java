package de.eitco.mavenizer;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import de.eitco.mavenizer.ManifestAnalyzer.Attribute;
import de.eitco.mavenizer.ManifestAnalyzer.ScoredValue;


public class Main {

	private static final ManifestAnalyzer manifestAnalyzer = new ManifestAnalyzer();
	
	public static void main(String[] args) throws IOException {
		
		var manifestAttributesInclude = List.of(
			"Created-By",
			"Extension-Name",
			"Built-By",
			"Main-Class",
			"Package",
			"Export-Package",
			"Automatic-Module-Name",
			"Bundle-Name",
			"Bundle-Activator",
			"Bundle-SymbolicName",
			"Bundle-Version",
			"Specification-Title",
			"Specification-Vendor-Id",
			"Specification-Version",
			"Implementation-Title",
			"Implementation-Vendor-Id",
			"Implementation-Version"
		);
		
		var manifestAttributesExclude = List.of(
			"Manifest-Version",
			"Archiver-Version",
			"Import-Package",
			"Export-Package",
			"Include-Resource"
		);
		
		var jarsDir = Paths.get("./jars");
		
		try (Stream<Path> files = Files.list(jarsDir)){
		    files.forEach(path -> {
				try (var fin = new FileInputStream(path.toFile())) {
					
					var manifest = readJarStream(fin, (entry, in) -> {
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
					if (manifest.isPresent()) {
						String jarName = path.getFileName().toString();
						System.out.println(jarName);
						
						var result = manifestAnalyzer.analyze(manifest.get());
						var scoreComparator = Comparator.comparing((ValueCandidate candidate) -> candidate.scoredValue.confidence).reversed();
						
						for (var uidComponent : MavenUidComponent.values()) {
							
							var resultList = result.get(uidComponent);
							resultList.sort(scoreComparator);
							
							if (resultList.size() > 0) {
								System.out.println("    " + uidComponent.name());
							}
							for (ValueCandidate candidate : resultList) {
								System.out.println("        " + rightPad(candidate.scoredValue.toString(), 25) + " (" + candidate.source.displayName + " -> " + candidate.sourceDetails.displaySource() + ")");
							}
						}
					}
					
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
		    });
		}
	}
	
	public static String rightPad(String str, int minLength) {
		if (str.length() < minLength) {
			int padLength = minLength - str.length();
			return str + " ".repeat(padLength);
		} else {
			return str;
		}
	}
	
	public static enum MavenUidComponent {
		GROUP_ID,
		ARTIFACT_ID,
		VERSION
	}
	
	public static enum Analyzer {
		MANIFEST("Manifest"),
		POM("Pom"),
		MAVEN_REPO_CHEK("Repo-Check");
		
		public final String displayName;
		private Analyzer(String displayName) {
			this.displayName = displayName;
		}
	}
	
	public static class ValueCandidate {
		public ScoredValue scoredValue;
		public Analyzer source;
		public ScoredValueSource sourceDetails;
		
		public ValueCandidate(ScoredValue scoredValue, Analyzer source, ScoredValueSource sourceDetails) {
			this.scoredValue = scoredValue;
			this.source = source;
			this.sourceDetails = sourceDetails;
		}
	}
	
	public static interface ScoredValueSource {
		public String displaySource();
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
