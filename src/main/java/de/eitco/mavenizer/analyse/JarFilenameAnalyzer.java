package de.eitco.mavenizer.analyse;

import java.util.regex.Matcher;

import de.eitco.mavenizer.AnalyzerService.Analyzer;
import de.eitco.mavenizer.AnalyzerService.MavenUidComponent;
import de.eitco.mavenizer.AnalyzerService.ValueCandidateCollector;

public class JarFilenameAnalyzer {
	
	public void analyze(ValueCandidateCollector result, String jarFilename) {
		
		// TODO also return name of jar (with version-suffix removed if possible) so we can identify jars to apply analyzer settings from a previous version of the software containing the jars
		
		var valueSource = "'" + jarFilename + "'";
		var nameWithoutExt = jarFilename.substring(0, jarFilename.lastIndexOf('.'));
		
		Matcher matcher = Helper.Regex.jarFilenameVersionSuffix.matcher(nameWithoutExt);
		if (matcher.find()) {
			String version = matcher.group(Helper.Regex.CAP_GROUP_VERSION);
			if (version != null) {
				int suffixStart = matcher.start();
				var nameWithoutVersion = nameWithoutExt.substring(0, suffixStart);
				
				result.addCandidate(MavenUidComponent.ARTIFACT_ID, nameWithoutVersion, 6, valueSource);
				result.addCandidate(MavenUidComponent.VERSION, version, 6, valueSource);
				return;
			}
		}
		
		result.addCandidate(MavenUidComponent.ARTIFACT_ID, nameWithoutExt, 4, valueSource);
	}
	
	public Analyzer getType() {
		return Analyzer.JAR_FILENAME;
	}
}
