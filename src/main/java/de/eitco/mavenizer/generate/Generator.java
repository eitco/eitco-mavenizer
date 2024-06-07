package de.eitco.mavenizer.generate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import de.eitco.mavenizer.analyze.ConsolePrinter;
import de.eitco.mavenizer.analyze.JarAnalyzer;
import de.eitco.mavenizer.analyze.OnlineAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eitco.mavenizer.AnalysisReport;
import de.eitco.mavenizer.Cli;
import de.eitco.mavenizer.StringUtil;
import de.eitco.mavenizer.Util;

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
	
	public static final String COMMAND_NAME = "generate";
	
	private static final Logger LOG = LoggerFactory.getLogger(Generator.class);
	
	private static final boolean POM_VERSION_PROPS = true;
	
	private static final String POWERSHELL_EXIT_ON_MVN_ERROR_COMMAND = "if (-not $?) {exit 1}";
	
	private final GeneratorArgs args = new GeneratorArgs();
	private final Cli cli;

	public Generator(Cli cli) {
		this.cli = cli;
	}
	
	public void addCommand(BiConsumer<String, Object> addCommand) {
		addCommand.accept(COMMAND_NAME, args);
	}
	
	public void runGenerator() {

		var validators = List.of(
				args.validateMain(),
				args.validateScriptTypes(),
				args.validateScriptFile(),
				args.validatePomFile()
		);
		if (!Util.validateArgs(cli, validators)) {
			return;
		}
		
		LOG.info("Generator started.");
		
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
			cli.println("Warning(s) above can be ignored, but will cause generated install script to contain incorrect file paths!");
			cli.askUserToContinue("    ");
		}
		
		// TODO maybe check again with MavenRepoChecker
		cli.println("The following jars were found online according to their reports.");
		cli.println("Therefore they will be excluded from install script(s).");
		jarReports.stream()
				.filter(jar -> jar.foundOnRemote)
				.forEach(jar -> {
					cli.println("    " + StringUtil.rightPad(jar.filename, 25) + "  ( " + jar.dir + " )");
				});
		cli.println();
		
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
							fileContent.append("; ");
							fileContent.append(POWERSHELL_EXIT_ON_MVN_ERROR_COMMAND);
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
