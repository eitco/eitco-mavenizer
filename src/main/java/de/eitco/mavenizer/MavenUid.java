package de.eitco.mavenizer;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class MavenUid {
	
	public static enum MavenUidComponent {
		GROUP_ID("groupId"),
		ARTIFACT_ID("artifactId"),
		VERSION("version");
		
		public final String xmlTagName;
		private MavenUidComponent(String xmlTagName) {
			this.xmlTagName = xmlTagName;
		}
	}
	
	public final String groupId;
	public final String artifactId;
	public final String version;
	public final String classifier;
	
	@JsonCreator
	public MavenUid(
			@JsonProperty("groupId") String groupId,
			@JsonProperty("artifactId") String artifactId,
			@JsonProperty("version") String version) {
		this.groupId = groupId;
		this.artifactId = artifactId;
		this.version = version;
		this.classifier = "";
	}
	
	public String get(MavenUidComponent component) {
		switch(component) {
		case ARTIFACT_ID: return artifactId;
		case GROUP_ID: return groupId;
		case VERSION: return version;
		}
		throw new IllegalStateException();
	}
	
	@Override
	public String toString() {
		var versionString = version == null ? "<unknown-version>" : version;
		var classifierString = classifier == "" ? "" : (" | classifier: " + classifier);
		return "( " + groupId + " | " + artifactId + " | " + versionString + classifierString + " )";
	}
	@Override
	public int hashCode() {
		return Objects.hash(artifactId, groupId, version, classifier);
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null) return false;
		if (getClass() != obj.getClass()) return false;
		MavenUid other = (MavenUid) obj;
		return Objects.equals(artifactId, other.artifactId)
				&& Objects.equals(groupId, other.groupId)
				&& Objects.equals(version, other.version)
				&& Objects.equals(classifier, other.classifier);
	}
}