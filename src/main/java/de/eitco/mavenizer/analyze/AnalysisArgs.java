package de.eitco.mavenizer.analyze;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import de.eitco.mavenizer.Cli.ResettableCommand;
import de.eitco.mavenizer.Util;

@Parameters(commandDescription = "Analyze jars interactively to generate report with maven uid for each jar.")
public class AnalysisArgs implements ResettableCommand {
	
	public static final String DATETIME_SUBSTITUTE = "<datetime>";
	
	@Parameter(order = 10, description = "<path(s) to jar file(s) or parent folder(s)>", required = true)
	public List<String> jars;
	
	@Parameter(order = 20, names = { "-interactive", "-i" }, description = 
			"Enable interactive mode to complete missing maven UID information for jars from unknown origin."
			+ " If disabled, only jars are added to final report that could be found in a maven remote repository.")
	public boolean interactive;
	
	// TODO user should be allowed to enclose each URL in double quotes to prevent URLs containing commas messing up the list
	@Parameter(order = 30, names = "-remoteRepos", description = 
			"Comma-separated list of remote maven repositories that are used to find identical jars."
			+ " If not specified, repositories found in user's settings.xml's default profile are used. If specified, settings.xml will be ignored.")
	public List<String> remoteRepos;
	
	@Parameter(order = 40, names = "-reportFile", description = "Only if -interactive is enabled: File path and name were result report should be created.")
	public String reportFile;
	
	@Parameter(order = 50, names = "-forceDetailedOutput", description = "Show full analysis results even when jar was found online.")
	public boolean forceDetailedOutput;
	
	@Parameter(order = 60, names = "-offline", description = "Disable attempts to find identical jars in remote repositories.")
	public boolean offline;
	
	@Parameter(order = 70, names = "-limit", description = "If set to a positive number, only that many jars will be analyzed.")
	public int limit;
	
	@Parameter(order = 80, names = "-start", description = "If set to a positive number, jars are skipped until jar with given number is reached.")
	public int start;
	
	public AnalysisArgs() {
		setDefaults();
	}
	
	@Override
	public void setDefaults() {
		jars = null;
		interactive = false;
		remoteRepos = null;
		reportFile = "./eitco-mavenizer-report-" + DATETIME_SUBSTITUTE + ".json";
		forceDetailedOutput = false;
		offline = false;
		limit = -1;
		start = 1;
	}
	
	public Optional<String> validateJars() {
		for (var jar : jars) {
			Path path = Paths.get(jar);
			File file = path.toFile();
			if (!file.exists()) {
				return Optional.of("Jar file/folder path '" + path + "' does not exist!");
			} else {
				if (file.isFile() && !path.getFileName().toString().toLowerCase().endsWith(".jar")) {
					return Optional.of("Wrong file extension for file  '" + path + "'! Expected .jar extension.");
				}
			}
		}
		return Optional.empty();
	}
	
	public Optional<String> validateReportFile() {
		if (reportFile != null) {
			var corrected = reportFile.replace(AnalysisArgs.DATETIME_SUBSTITUTE, "");
			// since filename arg might not contain datetime pattern, we need to check for existing files as well as parent dir
			return Util.validateFileCanBeCreated(corrected);
		}
		return Optional.empty();
	}
	
	public Optional<String> validateStartNumber() {
		if (start <= 0) {
			return Optional.of("Start parameter must be at least '1'.");
		}
		return Optional.empty();
	}
}