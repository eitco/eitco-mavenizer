package de.eitco.mavenizer.generate;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import de.eitco.mavenizer.Util;
import de.eitco.mavenizer.generate.Generator.ScriptType;

@Parameters(commandDescription = "Generate install script or pom.xml from report file created by analyzer.")
public class GeneratorArgs {
	
	@Parameter(order = 10, description = "<path(s) to report file(s) or parent folder(s)>", required = true)
	public List<String> reportFiles = null;
	
	@Parameter(order = 15, names = { "-scriptCommand" , "-c" }, description = "Command executed for each jar. Additional arguments can be included.")
	String scriptCommand = "mvn install:install-file";
	
	@Parameter(order = 20, names = "-noScript", description = "Disable install script generation.")
	boolean noScript = false;
	
	@Parameter(order = 30, names = "-scriptType", description = "Which script language(s) to generate. Currently supports only 'ps1' (powershell).")
	public Set<String> scriptTypes = Set.of(ScriptType.POWERSHELL.fileExtension);
	
	@Parameter(order = 40, names = "-scriptFile", description = "Name of install script output file (without file extension).")
	String scriptFile = "eitco-mavenizer-install";
	
	@Parameter(order = 50, names = "-pom", description = "Enable generation of pom.xml with dependencies from report.")
	boolean pom = false;
	
	@Parameter(order = 60, names = "-pomFile", description = "Only if -pom is enabled: Name of pom output file.")
	String pomFile = "eitco-mavenizer-pom.xml";

	
	public Optional<String> validateMain() {
		for (var reportFile : reportFiles) {
			Path path = Paths.get(reportFile);
			File file = path.toFile();
			if (!file.exists()) {
				return Optional.of("Report file '" + path + "' does not exist or is not a file!");
			} else{
				if (file.isFile() && !path.getFileName().toString().toLowerCase().endsWith(".json")) {
					return Optional.of("Wrong file extension for file  '" + path + "'! Expected .json extension.");
				}
			}
		}
		return Optional.empty();
	}
	
	public Optional<String> validateScriptTypes() {
		if (!noScript) {
			var fileExtensions = ScriptType.fileExtensions;
			for (var scriptType : scriptTypes) {
				if (!fileExtensions.containsKey(scriptType)) {
					return Optional.of("Script type '" + scriptType + "' is not supported! Expected one of: " + fileExtensions);
				}
			}
		}
		return Optional.empty();
	}
	
	public Optional<String> validateScriptFile() {
		if (!noScript) {
			for (var type : scriptTypes) {
				var error = Util.validateFileCanBeCreated(pomFile + "." + type);
				if (error.isPresent()) {
					return error;
				}
			}
		}
		return Optional.empty();
	}
	
	public Optional<String> validatePomFile() {
		if (pom) {
			return Util.validateFileCanBeCreated(pomFile);
		}
		return Optional.empty();
	}
}