package de.eitco.mavenizer.analyze.jar;

import java.util.Map;

import de.eitco.mavenizer.MavenUid.MavenUidComponent;
import de.eitco.mavenizer.analyze.JarAnalyzer.JarAnalyzerType;
import de.eitco.mavenizer.analyze.JarAnalyzer.ValueCandidateCollector;
import de.eitco.mavenizer.analyze.ValueCandidate;

public class PostAnalyzer {

	public void analyze(ValueCandidateCollector result, Map<MavenUidComponent, Map<String, ValueCandidate>> candidates) {
		// this analyzer deduces additional candidates from the info gathered by the other analyzers
		
		// Apache Commons - unusual groupId (equal to artifactId in most cases)
		int matchesPackageScore = 0;
		for (var groupId : candidates.get(MavenUidComponent.GROUP_ID).values()) {
			if (groupId.value.startsWith("org.apache.commons")) {
				matchesPackageScore += groupId.getScoreSum();
			}
		}
		if (matchesPackageScore >= 4) {
			for (var artifactId : candidates.get(MavenUidComponent.ARTIFACT_ID).values()) {
				boolean matchesName = artifactId.value.startsWith("commons-") && artifactId.getScoreSum() >= 4;
				if (matchesName) {
					result.addCandidate(MavenUidComponent.GROUP_ID, artifactId.value, 5, "Suspecting 'Apache Commons' jar - Rule: groupId equals artifactId");
					break;
				}
			}
		}
	}
	
	public JarAnalyzerType getType() {
		return JarAnalyzerType.POST;
	}
}
