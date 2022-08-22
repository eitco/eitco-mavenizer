package de.eitco.mavenizer;

import java.util.Objects;

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
	
	public MavenUid(String groupId, String artifactId, String version) {
		this.groupId = groupId;
		this.artifactId = artifactId;
		this.version = version;
	}
	
	String get(MavenUidComponent component) {
		switch(component) {
		case ARTIFACT_ID: return artifactId;
		case GROUP_ID: return groupId;
		case VERSION: return version;
		}
		throw new IllegalStateException();
	}
	
	@Override
	public String toString() {
		return "( " + groupId + " | " + artifactId + " | " + (version == null ? "<unknown-version>" : version) + " )";
	}
	@Override
	public int hashCode() {
		return Objects.hash(artifactId, groupId, version);
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null) return false;
		if (getClass() != obj.getClass()) return false;
		MavenUid other = (MavenUid) obj;
		return Objects.equals(artifactId, other.artifactId) && Objects.equals(groupId, other.groupId) && Objects.equals(version, other.version);
	}
}