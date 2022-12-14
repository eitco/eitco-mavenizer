package de.eitco.mavenizer.analyze.jar;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import de.eitco.mavenizer.MavenUid.MavenUidComponent;
import de.eitco.mavenizer.analyze.JarAnalyzer.FileBuffer;
import de.eitco.mavenizer.analyze.JarAnalyzer.JarAnalyzerType;
import de.eitco.mavenizer.analyze.JarAnalyzer.PomFileType;
import de.eitco.mavenizer.analyze.JarAnalyzer.ValueCandidateCollector;

public class PomAnalyzer {

	public void analyze(ValueCandidateCollector result, List<FileBuffer> pomFiles) {
		
		if (pomFiles.isEmpty()) {
			return;
		}
		
		Map<MavenUidComponent, Map<String, List<String>>> foundValues = findValueCandidatesWithSources(pomFiles);
		
		for (var uidComponent : MavenUidComponent.values()) {
			Map<String, List<String>> valuesWithSources = foundValues.get(uidComponent);
			
			// we expect no differences between xml, properties and path and no missing values (null)
			var isExpectedResults = valuesWithSources.size() == 1 && !valuesWithSources.containsKey(null);
			
			for (var valueWithSourcesEntry : valuesWithSources.entrySet()) {
				var value = valueWithSourcesEntry.getKey();
				if (isExpectedResults) {
					// 1 result, high confidence
					var confidence = 10;
					var source = Stream.of(PomFileType.values())
							.map(type -> type.filename)
							.collect(Collectors.joining(" / "));
					result.addCandidate(uidComponent, value, confidence, source);
					break;
				} else {
					// we add each result with low confidence, duplicates will be aggregated later anyway
					var confidence = 2;
					for (var source : valueWithSourcesEntry.getValue()) {
						result.addCandidate(uidComponent, value, confidence, source);
					}
				}
			}
		}
	}
	
	/**
	 * @return inner maps can contain null entry if value could not be read from one or more sources
	 */
	private Map<MavenUidComponent, Map<String, List<String>>> findValueCandidatesWithSources(List<FileBuffer> pomFiles) {
		
		var foundValues = Map.<MavenUidComponent, Map<String, List<String>>>of(
				MavenUidComponent.GROUP_ID, new HashMap<String, List<String>>(),
				MavenUidComponent.ARTIFACT_ID, new HashMap<String, List<String>>(),
				MavenUidComponent.VERSION, new HashMap<String, List<String>>()
				);
		
		boolean correctPath = true;
		Function<String, List<String>> listConstructor = key -> new ArrayList<>(4);// we expect 4 value sources (2 files, 2 paths) per jar
		
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
						
						var pathValueSource = "Path: '" + file.path + "'";
						foundValues.get(MavenUidComponent.GROUP_ID).computeIfAbsent(groupId, listConstructor).add(pathValueSource);
				        foundValues.get(MavenUidComponent.ARTIFACT_ID).computeIfAbsent(artifactId, listConstructor).add(pathValueSource);
					}
					
					// both pom.xml and pom.properties contain all Maven UID components, hopefully the exact same ones
					
					var fileValueSource = "File-Content: '" + pomFileType.filename + "'";
					
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
	
	public JarAnalyzerType getType() {
		return JarAnalyzerType.POM;
	}
}
