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
	
	@Parameter(order = 15, names = { "-interactive", "-i" }, description = 
			"Enable interactive mode to complete missing maven UID information for jars from unknown origin."
			+ "If disabled, only jars are added to final report that could be found in a maven remote repository.")
	public boolean interactive;
	
	@Parameter(order = 20, names = "-reportFile", description = "Only if -interactive is enabled: File path and name were result report should be created.")
	public String reportFile;
	
	@Parameter(order = 30, names = "-forceDetailedOutput", description = "Show full analysis results even when jar was found online.")
	public boolean forceDetailedOutput;
	
	@Parameter(order = 40, names = "-offline", description = "Disable attempts to find identical jars in remote repositories.")
	public boolean offline;
	
	@Parameter(order = 50, names = "-limit", description = "If set to a positive number, only that many jars will be analyzed.")
	public int limit;
	
	public AnalysisArgs() {
		setDefaults();
	}
	
	@Override
	public void setDefaults() {
		jars = null;
		interactive = false;
		reportFile = "./eito-mavenizer-report-" + DATETIME_SUBSTITUTE + ".json";
		forceDetailedOutput = false;
		offline = false;
		limit = -1;
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
}