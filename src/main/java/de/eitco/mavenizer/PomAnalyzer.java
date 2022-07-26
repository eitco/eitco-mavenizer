package de.eitco.mavenizer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

import javax.management.RuntimeErrorException;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import de.eitco.mavenizer.Main.Analyzer;
import de.eitco.mavenizer.Main.MavenUidComponent;
import de.eitco.mavenizer.Main.ValueSource;
import de.eitco.mavenizer.Main.StringValueSource;
import de.eitco.mavenizer.Main.ValueCandidate;
import de.eitco.mavenizer.ManifestAnalyzer.ScoredValue;

public class PomAnalyzer {
	
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

	public Map<MavenUidComponent, List<ValueCandidate>> analyze(List<FileBuffer> pomFiles) {
		
		var result = Map.<MavenUidComponent, List<ValueCandidate>>of(
				MavenUidComponent.GROUP_ID, new ArrayList<ValueCandidate>(),
				MavenUidComponent.ARTIFACT_ID, new ArrayList<ValueCandidate>(),
				MavenUidComponent.VERSION, new ArrayList<ValueCandidate>()
				);
		
		if (pomFiles.size() < 1) {
			return result;
		}
		
		Map<MavenUidComponent, Map<String, List<ValueSource>>> foundValues = findValueCandidatesWithSources(pomFiles);
		
		for (var uidComponent : MavenUidComponent.values()) {
			Map<String, List<ValueSource>> valuesWithSources = foundValues.get(uidComponent);
			
			for (var valueWithSourcesEntry : valuesWithSources.entrySet()) {
				var value = valueWithSourcesEntry.getKey();
				if (valuesWithSources.size() == 1) {
					// no differences between xml, properties and path -> high confidence
					var confidence = 8;
					var source = new StringValueSource("pom.xml / pom.properties");
					result.get(uidComponent).add(new ValueCandidate(new ScoredValue(value, confidence), Analyzer.POM, source));
					break;
				} else {
					// differences -> we add each with low confidence, duplicates will be aggregated later anyway
					var confidence = 2;
					for (var source : valueWithSourcesEntry.getValue()) {
						result.get(uidComponent).add(new ValueCandidate(new ScoredValue(value, confidence), Analyzer.POM, source));
					}
				}
			}
		}
		
		return result;
	}
	
	private Map<MavenUidComponent, Map<String, List<ValueSource>>> findValueCandidatesWithSources(List<FileBuffer> pomFiles) {
		
		var foundValues = Map.<MavenUidComponent, Map<String, List<ValueSource>>>of(
				MavenUidComponent.GROUP_ID, new HashMap<String, List<ValueSource>>(),
				MavenUidComponent.ARTIFACT_ID, new HashMap<String, List<ValueSource>>(),
				MavenUidComponent.VERSION, new HashMap<String, List<ValueSource>>()
				);
		
		boolean correctPath = true;
		Function<String, List<ValueSource>> listConstructor = key -> key == null ? null : new ArrayList<>(4);// we expect 4 value sources (2 files, 2 paths) per jar
		
		try {
			for (var file : pomFiles) {
				var filename = file.path.getFileName().toString();
				
				final PomFileType pomFileType;
				if (filename.equals(PomFileType.POM_XML.filename)) {
					pomFileType = PomFileType.POM_XML;
				}
				else if (filename.equals(PomFileType.POM_PROPS.filename)) {
					pomFileType = PomFileType.POM_PROPS;
				} else {
					pomFileType = null;
				}
				
				if (pomFileType != null) {
					
					// pom.xml/pom.properties should be located in /META-INF/maven/{groupId}/{artifactId}/, extract values from path first
					
					var firstParent = file.path.getParent();
					var secondParent = firstParent != null ? firstParent.getParent() : null;
					var thirdParent = secondParent != null ? secondParent.getParent() : null;
					correctPath = correctPath && firstParent != null && secondParent != null && thirdParent != null
							&& thirdParent.toString().equals("META-INF" + File.pathSeparator + "maven");

					if (correctPath) {
						var groupId = secondParent.getFileName().toString();
						var artifactId = firstParent.getFileSystem().toString();
						
						var pathValueSource = new StringValueSource("Path: '" + file.path + "'");
						foundValues.get(MavenUidComponent.GROUP_ID).computeIfAbsent(groupId, listConstructor).add(pathValueSource);
				        foundValues.get(MavenUidComponent.ARTIFACT_ID).computeIfAbsent(artifactId, listConstructor).add(pathValueSource);
					}
					
					// both pom.xml and pom.properties contain all Maven UID components, hopefully the exact same ones
					
					var fileValueSource = new StringValueSource("File-Content: '" + pomFileType.filename + "'");
					
					if (pomFileType == PomFileType.POM_XML) {
						
						MavenXpp3Reader reader = new MavenXpp3Reader();
				        Model model = reader.read(new ByteArrayInputStream(file.content));
				        
				        foundValues.get(MavenUidComponent.ARTIFACT_ID).computeIfAbsent(model.getArtifactId(), listConstructor).add(fileValueSource);
				        var groupId = model.getGroupId();
				        var version = model.getVersion();
				        if (groupId == null && model.getParent() != null) {
				        	groupId = model.getParent().getGroupId();
				        }
				        if (version == null && model.getParent() != null) {
				        	version = model.getParent().getVersion();
				        }
				        foundValues.get(MavenUidComponent.GROUP_ID).computeIfAbsent(groupId, listConstructor).add(fileValueSource);
				        foundValues.get(MavenUidComponent.VERSION).computeIfAbsent(version, listConstructor).add(fileValueSource);
					}
					if (pomFileType == PomFileType.POM_PROPS) {
						
						Properties properties = new Properties();
						properties.load(new ByteArrayInputStream(file.content));
						
						for (var component : MavenUidComponent.values()) {
							var value = properties.getProperty(component.xmlTagName);
							foundValues.get(component).computeIfAbsent(value, listConstructor).add(fileValueSource);
						}
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (XmlPullParserException e) {
			throw new RuntimeException(e);
		}
		
		return foundValues;
	}
}
