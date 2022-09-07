package de.eitco.mavenizer;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public class AnalysisReport {
	
	public static class AnalysisInfo {
		public boolean onlineCheckEnabled;
		public List<String> remoteRepos;
		
		@SuppressWarnings("unused")
		private AnalysisInfo() {
			// for deserializer
		} 
		public AnalysisInfo(boolean onlineCheckEnabled, List<String> remoteRepos) {
			super();
			this.onlineCheckEnabled = onlineCheckEnabled;
			this.remoteRepos = remoteRepos;
		}
	}
	
	@JsonIgnoreProperties({"onlineCheck"})
	public static class JarReport {
		public String filename;
		public String dir;
		public String sha256;
		public boolean foundOnRemote = false;
		public MavenUid result;
		
		@SuppressWarnings("unused")
		private JarReport() {
			// for deserializer
		}
		public JarReport(String filename, String dir, String sha256, boolean foundOnRemote, MavenUid result) {
			this.filename = filename;
			this.dir = dir;
			this.sha256 = sha256;
			this.foundOnRemote = foundOnRemote;
			this.result = result;
		}
	}
	
	public String schemaVersion = "1.0";
	
	public AnalysisInfo analysisInfo;
	public List<JarReport> jarResults;
	
	@SuppressWarnings("unused")
	private AnalysisReport() {
		// for deserializer
	}
	public AnalysisReport(AnalysisInfo analysisInfo, List<JarReport> jarResults) {
		this.analysisInfo = analysisInfo;
		this.jarResults = jarResults;
	}
}
