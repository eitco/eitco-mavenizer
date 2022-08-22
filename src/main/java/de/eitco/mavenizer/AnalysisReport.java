package de.eitco.mavenizer;

import java.util.List;

import de.eitco.mavenizer.analyze.MavenRepoChecker.OnlineMatch;

public class AnalysisReport {
	
	public static class AnalysisInfo {
		public boolean onlineCheckEnabled;
		public List<String> remoteRepos;
		
		private AnalysisInfo() {
			// for deserializer
		} 
		public AnalysisInfo(boolean onlineCheckEnabled, List<String> remoteRepos) {
			super();
			this.onlineCheckEnabled = onlineCheckEnabled;
			this.remoteRepos = remoteRepos;
		}
	}
	
	public static class JarReport {
		public String filename;
		public String dir;
		public String sha256;
		public OnlineMatch onlineCheck;
		public MavenUid result;
		
		private JarReport() {
			// for deserializer
		}
		public JarReport(String filename, String dir, String sha256, OnlineMatch onlineCheck, MavenUid result) {
			this.filename = filename;
			this.dir = dir;
			this.sha256 = sha256;
			this.onlineCheck = onlineCheck;
			this.result = result;
		}
	}
	
	public String schemaVersion = "1.0";
	
	public AnalysisInfo analysisInfo;
	public List<JarReport> jarResults;
	
	private AnalysisReport() {
		// for deserializer
	}
	public AnalysisReport(AnalysisInfo analysisInfo, List<JarReport> jarResults) {
		this.analysisInfo = analysisInfo;
		this.jarResults = jarResults;
	}
}
