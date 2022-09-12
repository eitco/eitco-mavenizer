# Eitco Mavenizer

Helps you to find or define Maven UIDs for any JAR file and generate corresponding artifact install scripts.

## How to use

There are two main commands:

### `analyze`
Analyze jar files to find their Maven UIDs (groupId, artifactId, version) and save that information to a json report file.

In interactive mode (`-i`), you can complete missing information if the tool could not determine the UID conclusively. In this case the tool will provide best guesses based on various analysis information (manifest, timestamps, filename, .class package structure, online remote repository information).
  
### `generate`

Use the report file created by the `analyze` command to create an maven install or deploy script. Scripts only contain the jars that could not be found online by the analyzer, since only those are missing from the remote(s) you are using.

Optionally generate a pom containing the artifacts from the report (requires local jars to have been installed).

### CLI Commands

```
-help, --help, -h

analyze      Analyze jars interactively to generate report with maven uid for each jar.
  Usage: analyze [options] <path(s) to jar file(s) or parent folder(s)>
	Options:
	  -interactive, -i
		Enable interactive mode to complete missing maven UID information for jars from unknown origin. If disabled, only jars are added to final report that could be found in a maven remote repository.
		Default: false
	  -reportFile
		Only if -interactive is enabled: File path and name were result report should be created.
		Default: ./eitco-mavenizer-report-<datetime>.json
	  -forceDetailedOutput
		Show full analysis results even when jar was found online.
		Default: false
	  -offline
		Disable attempts to find identical jars in remote repositories.
		Default: false
	  -limit
		If set to a positive number, only that many jars will be analyzed.
		Default: -1
	  -start
		If set to a positive number, jars are skipped until jar with given number is reached.
		Default: 1

generate      Generate install script or pom.xml from report file created by analyzer.
  Usage: generate [options] <path(s) to report file(s) or parent folder(s)>
	Options:
	  -scriptCommand, -c
		Command executed for each jar. Additional arguments can be included.
	  -noScript
		Disable install script generation.
		Default: false
	  -scriptType
		Which script language(s) to generate. Currently supports only 'ps1' (powershell).
	  -scriptFile
		Name of install script output file (without file extension).
	  -pom
		Enable generation of pom.xml with dependencies from report.
		Default: false
	  -pomFile
		Only if -pom is enabled: Name of pom output file.
```