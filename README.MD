[![License](https://img.shields.io/github/license/eitco/eitco-mavenizer.svg?style=for-the-badge)](https://opensource.org/license/mit)
[![Build status](https://img.shields.io/github/actions/workflow/status/eitco/eitco-mavenizer/deploy.yaml?branch=main&style=for-the-badge&logo=github)](https://github.com/eitco/eitco-mavenizer/actions/workflows/deploy.yaml)
[![Download](https://img.shields.io/github/v/release/eitco/eitco-mavenizer?style=for-the-badge&label=download&logo=github)](https://github.com/eitco/eitco-mavenizer/releases/latest/download/eitco-mavenizer.jar)


**This repository is currently not maintained!**

# Eitco Mavenizer

Helps you to find or define Maven UIDs/coordinates for any JAR file and generate corresponding artifact install scripts.

## Download & Execute
- [Download](https://github.com/eitco/eitco-mavenizer/releases/latest/download/eitco-mavenizer.jar) provides an executable "fat" JAR file
- Requires [Java 11 JRE or higher](https://adoptium.net/de/temurin/releases/?package=jre&os=windows&arch=x64) to be installed
- Execute with `java -jar eitco-mavenizer.jar`

## How to use

There are two main commands:

### `analyze`
Analyze jar files to find their Maven UIDs/coordinates (groupId, artifactId, version) and save that information to a json report file.

In interactive mode (`-i`), you can complete missing information if the tool could not determine the UID conclusively. In this case the tool will provide best guesses based on various analysis information (manifest, timestamps, filename, .class package structure, online remote repository information).
  
### `generate`

Use the report file created by the `analyze` command to create an maven install or deploy script. Scripts only contain the jars that could not be found online by the analyzer, since only those are missing from the remote(s) you are using.

Optionally generate a pom containing the artifacts from the report (requires local jars to have been installed).

### Known issues

* Crashes on exit sometimes, leaving behind temporary files/folders - can be deleted manually, otherwise tool will delete them on next run

### Example Analysis Output

When executing ```C:\test> java -jar .\eitco-mavenizer.jar analyze ./jars -forceDetailedOutput```, where `jars` is a directory containing `activation.jar`:

```
activation.jar (1/1)
    Found identical jar online, UID: ( javax.activation | activation | 1.1 )
    Forced details:

    SHA_256 (uncompressed): 7J4ovUbYqujZRFnJCcjlsON3HBtSJv1QLKVEbZ9COx8=

    OFFLINE RESULT
        GROUP_ID
             6 | com.sun            (6 | Manifest -> Implementation-Vendor-Id: 'com.sun')
             5 | javax.activation   (4 | Manifest -> Extension-Name: 'javax.activation')
                                    (1 | Class-Filepath -> Path contains  71% of classes: 'javax\activation')
        ARTIFACT_ID
             6 | activation        (4 | Jar-Filename -> 'activation.jar')
                                   (2 | Manifest -> Extension-Name: 'javax.activation')
             1 | sun               (1 | Manifest -> Implementation-Vendor-Id: 'com.sun')
        VERSION
             5 | 1.1               (3 | Manifest -> Implementation-Version: '1.1')
                                   (2 | Manifest -> Specification-Version: '1.1')
             1 | 2006.05.02        (1 | Class-Timestamp -> 100% of classes have created/modified date: 2006-05-02)

    ONLINE RESULT
          FOUND_MATCH_EXACT_SHA   FOR ( javax.activation | activation | 1.1 ) AT https://repo1.maven.org/maven2/javax/activation/activation/1.1/activation-1.1.jar
        Found artifactId / groupId pairs online, comparing local jar with random online versions:
          PAIR:             ( javax.activation | activation | <unknown-version> )
            NOT_FOUND   FOR ( javax.activation | activation | 1.0.2 )
            FOUND_NO_MATCH   FOR ( javax.activation | activation | 1.1.1 ) AT https://repo1.maven.org/maven2/javax/activation/activation/1.1.1/activation-1.1.1.jar
```
Offline analysis:
 - On the left are the highest scoring candidates for each coordinate component  
   (`6 | com.sun` means that the potential groupId `com.sun` scored a combined weight of 6 points).
 - On the right are the sources accumulated for each candidate with their individual scores  
   (`4 | Manifest -> Extension-Name: 'javax.activation'` means that the jar manifest contained the string `javax.activation` in the `Extension-Name` attribute, which was given a score of 4 points by the analyzer)

Online analysis:
- On the left is the status of each online jar search/comparison:
  - `FOUND_MATCH_EXACT_SHA` means that a JAR was found online and its content matches the local JAR exactly
  - `FOUND_MATCH_EXACT_CLASSES_SHA` is similar to `FOUND_MATCH_EXACT_SHA` except that some non-class files did not match
  - `FOUND_NO_MATCH` means that a JAR was found online at the given coordinates but does not match the local JAR
- If the two highest scoring version candidates do not score above a certain threshold, `<unknown-version>` indicates that the analyzer just randomly chooses versions that actually exist online instead of trying to use the low-scoring version value (`2006.05.02` in this case) for finding/downloading/comparing online jars with the local jar.

## CLI Documentation

```
-help, --help, -h

analyze      Analyze jars interactively to generate report with maven uid for each jar.
  Usage: analyze [options] <path(s) to jar file(s) or parent folder(s)>
    Options:
      -interactive, -i
        Enable interactive mode to complete missing maven UID information for jars from unknown origin. If disabled, only jars are added to final report that could be found in a maven remote repository.
        Default: false
      -remoteRepos
        Comma-separated list of remote maven repositories that are used to find identical jars. If not specified, repositories found in user's settings.xml's default profile are used. If specified, settings.xml will be ignored.
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
        Default: mvn install:install-file
      -noScript
        Disable install script generation.
        Default: false
      -scriptType
        Which script language(s) to generate. Currently supports only 'ps1' (powershell).
        Default: [ps1]
      -scriptFile
        Name of install script output file (without file extension).
        Default: eitco-mavenizer-install
      -pom
        Enable generation of pom.xml with dependencies from report.
        Default: false
      -pomFile
        Only if -pom is enabled: Name of pom output file.
        Default: eitco-mavenizer-pom.xml
```
