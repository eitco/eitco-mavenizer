package de.eitco.mavenizer;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.eitco.mavenizer.Cli.ResettableCommand;

public class Generator {

	public static enum ScriptType {
		POWERSHELL("ps1");
		
		public static Map<String, ScriptType> fileExtensions = Arrays.stream(ScriptType.values())
				.collect(Collectors.toMap((type -> type.fileExtension), Function.identity()));

		public final String fileExtension;
		ScriptType(String fileExtension) {
			this.fileExtension = fileExtension;
		}
	}
	
	@Parameters(commandDescription = "Generate install script or pom.xml from report file created by analyzer.")
	public static class GeneratorArgs implements ResettableCommand {
		
		@Parameter(order = 10, description = "<path(s) to report file(s)>", required = true)
		public List<String> reportFiles;
		
		@Parameter(order = 20, names = "-noScript", description = "Disable install script generation.")
		boolean noScript;
		
		@Parameter(order = 30, names = "-scriptType", description = "Which script language(s) to generate. Currently supports only 'ps1' (powershell).")
		public Set<String> scriptTypes;
		
		@Parameter(order = 40, names = "-scriptFile", description = "Name of install script output file (without file extension).")
		String scriptFile;
		
		@Parameter(order = 50, names = "-pom", description = "Enable generation of pom.xml with dependencies from report.")
		boolean pom;
		
		@Parameter(order = 60, names = "-pomFile", description = "Only if -pom is enabled: Name of pom output file.")
		String pomFile;

		@Override
		public void setDefaults() {
			reportFiles = null;
			scriptTypes = new HashSet<>();
			scriptTypes.add(ScriptType.POWERSHELL.fileExtension);
			scriptFile = "eitco-mavenizer-install";
			pom = false;
			pomFile = "eitco-mavenizer-pom.xml";
		}
		
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
					return Util.validateFileCanBeCreated(pomFile + "." + type);
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
	
	public static final String COMMAND_NAME = "generate";
	
	private final GeneratorArgs args = new GeneratorArgs();
	
	public void addCommand(Cli cli) {
		cli.addCommand(COMMAND_NAME, args);
	}
	
	public void runGenerator(Cli cli) {
		
		cli.validateArgsOrRetry(COMMAND_NAME, () -> {
			var errors = List.of(
					args.validateMain(),
					args.validateScriptTypes(),
					args.validateScriptFile(),
					args.validatePomFile()
				);
			return errors;
		});
		
		ObjectMapper mapper = new ObjectMapper();
		var analysisReports = new ArrayList<AnalysisReport>();
		try {
			for (var file : args.reportFiles) {
				analysisReports.add(mapper.readValue(new File(file), AnalysisReport.class));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		
		if (!args.noScript) {
			for (var scriptType : args.scriptTypes) {
				var type = ScriptType.fileExtensions.get(scriptType);
				
				String fileContent = "";
				if (type.equals(ScriptType.POWERSHELL)) {
					
					for (var report : analysisReports) {
						for (var jar : report.jarResults) {
							var jarPath = Paths.get(jar.dir).resolve(jar.filename);
							var uid = jar.result;
							var repositoryId = "";
							var repositoryUrl = "";
							fileContent += ".\\mvn deploy:deploy-file -Dfile='" + jarPath + "' -DgroupId='" + uid.groupId + "' -DartifactId='" + uid.artifactId
									+ "' -Dpackaging='jar' -Dversion='1.0' -DgeneratePom='true' -DrepositoryId='" + repositoryId + "' -Durl='" + repositoryUrl + "'"
									+ "\n";
						}
					}
				}
				
				var path = Paths.get(args.scriptFile + "." + type.fileExtension);
				try {
					Files.writeString(path, fileContent);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
		}
	}
}