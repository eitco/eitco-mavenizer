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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.eitco.mavenizer.Cli.ResettableCommand;

public class Generator {

	public enum ScriptType {
		POWERSHELL("ps1");
		
		public static final Map<String, ScriptType> fileExtensions = Arrays.stream(ScriptType.values())
				.collect(Collectors.toMap((type -> type.fileExtension), Function.identity()));

		public final String fileExtension;
		ScriptType(String fileExtension) {
			this.fileExtension = fileExtension;
		}
	}
	
	@Parameters(commandDescription = "Generate install script or pom.xml from report file created by analyzer.")
	public static class GeneratorArgs implements ResettableCommand {
		
		@Parameter(order = 10, description = "<path(s) to report file(s) or parent folder(s)>", required = true)
		public List<String> reportFiles;
		
		@Parameter(order = 15, names = { "-scriptCommand" , "-c" }, description = "Command executed for each jar. Additional arguments can be included.")
		String scriptCommand;
		
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
		
		public GeneratorArgs() {
			setDefaults();
		}

		@Override
		public void setDefaults() {
			reportFiles = null;
			scriptCommand = "mvn install:install-file";
			noScript = false;
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
	
	public static final String COMMAND_NAME = "generate";
	
	private static final Logger LOG = LoggerFactory.getLogger(Generator.class);
	
	private static final boolean POM_VERSION_PROPS = true;
	
	private final GeneratorArgs args = new GeneratorArgs();
	
	public void addCommand(Cli cli) {
		cli.addCommand(COMMAND_NAME, args);
	}
	
	public void runGenerator(Cli cli) {
		
		cli.validateArgsOrRetry(COMMAND_NAME, () -> {
			return List.of(
					args.validateMain(),
					args.validateScriptTypes(),
					args.validateScriptFile(),
					args.validatePomFile()
				);
		});
		
		LOG.info("Generator started with args: " + Arrays.toString(cli.getLastArgs()));
		
		List<Path> reportPaths = Util.getFiles(args.reportFiles, path -> path.getFileName().toString().toLowerCase().endsWith(".json"));
		
		ObjectMapper mapper = new ObjectMapper();
		var analysisReports = new ArrayList<AnalysisReport>();
		try {
			for (var path : reportPaths) {
				analysisReports.add(mapper.readValue(path.toFile(), AnalysisReport.class));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		
		var jarReports = analysisReports.stream()
				.flatMap(report -> report.jarResults.stream())
				.collect(Collectors.toList());
		
		if (jarReports.isEmpty()) {
			cli.println("No jar entries found in given report files!", LOG::info);
			return;
		}
		
		boolean areAllJarFilesValid = true;
		for (var jar : jarReports) {
			var jarPath = Paths.get(jar.dir).resolve(jar.filename);
			var jarFile = jarPath.toFile();
			if (!jarFile.exists() || !jarFile.isFile()) {
				cli.println("Warning: Jar file not found: " + jarPath, LOG::warn);
				areAllJarFilesValid = false;
			} else {
				var actualHash = Util.sha256(jarFile).jarSha256;
				var expectedHash = jar.sha256;
				if (!actualHash.equals(expectedHash)) {
					cli.println("Warning: Found jar file has different content compared to jar from report: " + jarPath, LOG::warn);
					areAllJarFilesValid = false;
				}
			}
		}
		if (!areAllJarFilesValid) {
			System.out.println("Warning(s) above can be ignored, but will cause generated install script to contain incorrect file paths!");
			cli.askUserToContinue("    ");
		}
		
		// TODO maybe check again with MavenRepoChecker
		System.out.println("The following jars were found online according to their reports.");
		System.out.println("Therefore they will be excluded from install script(s).");
		jarReports.stream()
				.filter(jar -> jar.foundOnRemote)
				.forEach(jar -> {
					System.out.println("    " + StringUtil.rightPad(jar.filename, 25) + "  ( " + jar.dir + " )");
				});
		System.out.println();
		
		if (!args.noScript) {
			for (var scriptType : args.scriptTypes) {
				var type = ScriptType.fileExtensions.get(scriptType);
				
				var fileContent = new StringBuilder();
				if (type.equals(ScriptType.POWERSHELL)) {
					
					for (var jar : jarReports) {
						if (!jar.foundOnRemote) {
							var jarPath = Paths.get(jar.dir).resolve(jar.filename);
							var uid = jar.result;
							
							fileContent.append(args.scriptCommand);
							fileContent.append(" -Dpackaging='jar'");
							fileContent.append(" -Dfile='" + jarPath + "'");
							fileContent.append(" -DgroupId='" + uid.groupId + "'");
							fileContent.append(" -DartifactId='" + uid.artifactId + "'");
							fileContent.append(" -Dversion='" + uid.version + "'");
							if (uid.classifier != null && !uid.classifier.isBlank()) {
								fileContent.append(" -Dclassifier='" + uid.classifier + "'");
							}
							fileContent.append("\n");
						}
					}
				}
				
				var path = Paths.get(args.scriptFile + "." + type.fileExtension);
				cli.println("Generating install script: " + path.toAbsolutePath(), LOG::info);
				try {
					Files.writeString(path, fileContent);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
		}
		
		if (args.pom) {
			var fileContent = new StringBuilder();
			fileContent.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"" + "\n");
			fileContent.append("	xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"" + "\n");
			fileContent.append("	xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">" + "\n");
			fileContent.append("	<modelVersion>4.0.0</modelVersion>" + "\n");
			fileContent.append("	<groupId>???</groupId>" + "\n");
			fileContent.append("	<artifactId>???</artifactId>" + "\n");
			fileContent.append("	<version>0.0.1-SNAPSHOT</version>" + "\n");
			fileContent.append("" + "\n");
			
			if (POM_VERSION_PROPS) {
				fileContent.append("	<properties>" + "\n");
				for (var jar : jarReports) {
					var uid = jar.result;
					fileContent.append("		<version." + uid.artifactId + ">" + uid.version + "</version." + uid.artifactId +">" + "\n");
				}
				fileContent.append("	</properties>" + "\n");
				fileContent.append("" + "\n");
			}
			
			fileContent.append("	<dependencies>" + "\n");
			for (var jar : jarReports) {
				var uid = jar.result;
				fileContent.append("		<dependency>" + "\n");
				fileContent.append("			<groupId>" + uid.groupId + "</groupId>" + "\n");
				fileContent.append("			<artifactId>" + uid.artifactId + "</artifactId>" + "\n");
				if (POM_VERSION_PROPS) {
					fileContent.append("			<version>${version." + uid.artifactId + "}</version>" + "\n");
				} else {
					fileContent.append("			<version>" + uid.version + "</version>" + "\n");
				}
				if (uid.classifier != null && !uid.classifier.isBlank()) {
					fileContent.append("			<classifier>" + uid.classifier + "</classifier>" + "\n");
				}
				fileContent.append("		</dependency>" + "\n");
			}
			fileContent.append("	</dependencies>" + "\n");
			
			fileContent.append("</project>" + "\n");
			
			var path = Paths.get(args.pomFile);
			cli.println("Generating POM: " + path.toAbsolutePath(), LOG::info);
			try {
				Files.writeString(path, fileContent);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}
}
