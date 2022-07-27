package de.eitco.mavenizer.analyse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import de.eitco.mavenizer.AnalyzerService.Analyzer;
import de.eitco.mavenizer.AnalyzerService.MavenUidComponent;
import de.eitco.mavenizer.AnalyzerService.ScoredValue;
import de.eitco.mavenizer.AnalyzerService.StringValueSource;
import de.eitco.mavenizer.AnalyzerService.ValueCandidate;
import de.eitco.mavenizer.AnalyzerService.ValueSource;

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
			
			// we expect no differences between xml, properties and path and no missing values (null)
			var isExpectedResults = valuesWithSources.size() == 1 && !valuesWithSources.containsKey(null);
			
			for (var valueWithSourcesEntry : valuesWithSources.entrySet()) {
				var value = valueWithSourcesEntry.getKey();
				if (isExpectedResults) {
					// 1 result, high confidence
					var confidence = 8;
					var source = new StringValueSource("pom.xml / pom.properties");
					result.get(uidComponent).add(new ValueCandidate(new ScoredValue(value, confidence), Analyzer.POM, source));
					break;
				} else {
					// we add each result with low confidence, duplicates will be aggregated later anyway
					var confidence = 2;
					for (var source : valueWithSourcesEntry.getValue()) {
						result.get(uidComponent).add(new ValueCandidate(new ScoredValue(value, confidence), Analyzer.POM, source));
					}
				}
			}
		}
		
		return result;
	}
	
	/**
	 * @return inner maps can contain null entry if value could not be read from one or more sources
	 */
	private Map<MavenUidComponent, Map<String, List<ValueSource>>> findValueCandidatesWithSources(List<FileBuffer> pomFiles) {
		
		var foundValues = Map.<MavenUidComponent, Map<String, List<ValueSource>>>of(
				MavenUidComponent.GROUP_ID, new HashMap<String, List<ValueSource>>(),
				MavenUidComponent.ARTIFACT_ID, new HashMap<String, List<ValueSource>>(),
				MavenUidComponent.VERSION, new HashMap<String, List<ValueSource>>()
				);
		
		boolean correctPath = true;
		Function<String, List<ValueSource>> listConstructor = key -> new ArrayList<>(4);// we expect 4 value sources (2 files, 2 paths) per jar
		
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
					correctPath = correctPath && firstParent != null && secondParent != null && thirdParent != null;
					correctPath = correctPath && thirdParent.equals(Paths.get("META-INF/maven"));
					{
						var groupId = correctPath ? secondParent.getFileName().toString() : null;
						var artifactId = correctPath ? firstParent.getFileName().toString() : null;
						
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
