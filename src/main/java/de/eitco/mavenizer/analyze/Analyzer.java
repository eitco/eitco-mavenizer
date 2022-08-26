package de.eitco.mavenizer.analyze;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eitco.mavenizer.AnalysisReport;
import de.eitco.mavenizer.AnalysisReport.AnalysisInfo;
import de.eitco.mavenizer.AnalysisReport.JarReport;
import de.eitco.mavenizer.Cli;
import de.eitco.mavenizer.MavenUid;
import de.eitco.mavenizer.MavenUid.MavenUidComponent;
import de.eitco.mavenizer.StringUtil;
import de.eitco.mavenizer.Util;
import de.eitco.mavenizer.analyze.MavenRepoChecker.OnlineMatch;
import de.eitco.mavenizer.analyze.MavenRepoChecker.UidCheck;
import de.eitco.mavenizer.analyze.ValueCandidate.ValueSource;
import de.eitco.mavenizer.analyze.jar.ClassFilepathAnalyzer;
import de.eitco.mavenizer.analyze.jar.Helper.Regex;
import de.eitco.mavenizer.analyze.jar.JarFilenameAnalyzer;
import de.eitco.mavenizer.analyze.jar.ManifestAnalyzer;
import de.eitco.mavenizer.analyze.jar.PomAnalyzer;
import de.eitco.mavenizer.analyze.jar.PomAnalyzer.FileBuffer;
import de.eitco.mavenizer.analyze.jar.PomAnalyzer.PomFileType;

/**
 * TODO: Offline analysis should move to separate class.
 */
public class Analyzer {

	public static class Jar {
		public final String name;
		public final String dir;
		public final String sha256;
		
		public Jar(String name, String dir, String sha256) {
			this.name = name;
			this.dir = dir;
			this.sha256 = sha256;
		}
	}
	
	public static enum FileAnalyzer {
		MANIFEST("Manifest"),
		JAR_FILENAME("Jar-Filename"),
		POM("Pom"),
		CLASS_FILEPATH("Class-Filepath");
		
		public final String displayName;
		private FileAnalyzer(String displayName) {
			this.displayName = displayName;
		}
	}
	
	/**
	 * Consumer function that is used by FileAnalyzers to return any number of value candidates.
	 */
	@FunctionalInterface
	public static interface ValueCandidateCollector {
		void addCandidate(MavenUidComponent component, String value, int confidenceScore, String sourceDetails);
	}
	
	/**
	 * Helper function that extends ValueCandidateCollector to also provide FileAnalyzer type.
	 */
	@FunctionalInterface
	private static interface AnalyzerCandidateCollector {
		void addCandidate(FileAnalyzer analyzer, MavenUidComponent component, String value, int confidenceScore, String sourceDetails);
		
		default ValueCandidateCollector withAnalyzer(FileAnalyzer analyzer) {
			return (component, value, confidenceScore, sourceDetails) -> {
				this.addCandidate(analyzer, component, value, confidenceScore, sourceDetails);
			};
		}
	}
	
	public static class JarAnalysisWaitingForCompletion {
		public final Jar jar;
		public final Map<MavenUidComponent, List<ValueCandidate>> offlineResult;
		public final CompletableFuture<Set<UidCheck>> onlineCompletionWithVersion;
		public final CompletableFuture<Map<MavenUid, Set<UidCheck>>> onlineCompletionNoVersion;
		
		public JarAnalysisWaitingForCompletion(Jar jar, Map<MavenUidComponent, List<ValueCandidate>> offlineResult,
				CompletableFuture<Set<UidCheck>> onlineCompletionWithVersion,
				CompletableFuture<Map<MavenUid, Set<UidCheck>>> onlineCompletionNoVersion) {
			this.jar = jar;
			this.offlineResult = offlineResult;
			this.onlineCompletionWithVersion = onlineCompletionWithVersion;
			this.onlineCompletionNoVersion = onlineCompletionNoVersion;
		}
	}
	
	public static final String COMMAND_NAME = "analyze";
	
	private static final Logger LOG = LoggerFactory.getLogger(Analyzer.class);
	
	private final AnalysisArgs args = new AnalysisArgs();
	private final AnalyzerConsolePrinter printer = new AnalyzerConsolePrinter();
	
	private final ManifestAnalyzer manifestAnalyzer = new ManifestAnalyzer();
	private final JarFilenameAnalyzer jarNameAnalyzer = new JarFilenameAnalyzer();
	private final PomAnalyzer pomAnalyzer = new PomAnalyzer();
	private final ClassFilepathAnalyzer classAnalyzer = new ClassFilepathAnalyzer();
	
	private MavenRepoChecker repoChecker = null;
	
	public void addCommand(Cli cli) {
		cli.addCommand(COMMAND_NAME, args);
	}
	
	public void runAnalysis(Cli cli) {
		
		cli.validateArgsOrRetry(COMMAND_NAME, () -> {
			var errors = List.of(
					args.validateJars(),
					args.validateReportFile()
				);
			return errors;
		});
		
		LOG.info("Analyzer started with args: " + Arrays.toString(cli.getLastArgs()));
		
		if (args.interactive) {
			cli.println("Interactive mode enabled.", LOG::info);
		}
		
		if (!args.offline) {
			repoChecker = new MavenRepoChecker();
		} else {
			cli.println("ONLINE ANALYSIS DISABLED! - Analyzer will not be able to auto-select values for matching jars found online!", LOG::info);
			cli.askUserToContinue("");
		}
		
		cli.println("Offline-Analysis started.", LOG::info);
		
		List<Path> jarPaths = new ArrayList<Path>();
		for (var jarArg : args.jars) {
			Path fileOrDirAsPath = Paths.get(jarArg);
			File fileOrDir = fileOrDirAsPath.toFile();
			if (fileOrDir.isDirectory()) {
				try (Stream<Path> files = Files.list(fileOrDirAsPath)) {
					jarPaths.addAll(files
							.filter(path -> path.toFile().isFile())
							.filter(path -> path.getFileName().toString().endsWith(".jar"))
							.collect(Collectors.toList()));
			    } catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
			if (fileOrDir.isFile()) {
				jarPaths.add(fileOrDirAsPath);
			}
		}
		var jarCount = jarPaths.size();
		var jarIndex = 0;
		List<JarAnalysisWaitingForCompletion> waiting = new ArrayList<>(jarCount);
		
		// first we do offline analysis an start online analysis for all jars
	    for (var jarPath : jarPaths) {
	    	
	    	if (args.limit >= 0 && jarIndex >= args.limit) {
	    		break;
	    	}
	    	
			LOG.debug("Analyzing Jar: '" + jarPath.toString() + "'");
			System.out.print(StringUtil.RETURN_LINE + "Offline-Analysis: Jar " + (jarIndex + 1) + "/" + jarCount);
			
			try (var fin = new FileInputStream(jarPath.toFile())) {
				
				// We need two input streams because JarInputStream cannot read or expose uncompressed bytes, but we need those to create hash.
				// We hash uncompressed bytes so we know if the jar content is identical independent from jar compression level/method.
				var compressedBytes = fin.readAllBytes();
				InputStream compressedIn = new ByteArrayInputStream(compressedBytes);
				ZipInputStream unzipIn = new ZipInputStream(new ByteArrayInputStream(compressedBytes));
				
				String jarName = jarPath.getFileName().toString();
		    	String jarHash = Util.sha256(unzipIn);
		    	Jar jar = new Jar(jarName, jarPath.toAbsolutePath().getParent().toString(), jarHash);
				
				List<FileBuffer> pomFiles = new ArrayList<>(2);
				List<Path> classFilepaths = new ArrayList<>();
				
				var manifest = readJarStream(compressedIn, (entry, in) -> {
					
					var manualManifest = Optional.<Manifest>empty();
					var entryPath = Paths.get(entry.getName());
					var filename = entryPath.getFileName().toString();
					
					if (!entry.isDirectory()) {
						if (filename.equals(PomFileType.POM_XML.filename) || filename.equals(PomFileType.POM_PROPS.filename)) {
							try {
								var bytes = in.readAllBytes();
								pomFiles.add(new FileBuffer(entryPath, bytes));
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						}
						if (filename.endsWith(".class")) {
							classFilepaths.add(entryPath);
						}
						if (Paths.get("META-INF/MANIFEST.MF").equals(entryPath)) {
							// Usually the JarInputStream should parse the manifest and provide it separately from other files.
							// However, apparently it can fail to find or parse the file, in which case we need to parse it manually.
							LOG.debug("Failed to auto-parse manifest: " + jarName);
							try {
								manualManifest = Optional.of(new Manifest(in));
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						}
					}
					
					return manualManifest;
				});
				
				var collected = Map.<MavenUidComponent, Map<String, ValueCandidate>>of(
						MavenUidComponent.GROUP_ID, new HashMap<>(),
						MavenUidComponent.ARTIFACT_ID, new HashMap<>(),
						MavenUidComponent.VERSION, new HashMap<>()
						);
				
				AnalyzerCandidateCollector collector = (FileAnalyzer analyzer, MavenUidComponent component, String value, int confidenceScore, String sourceDetails) -> {
					Map<String, ValueCandidate> candidates = collected.get(component);
					
					var candidate = candidates.computeIfAbsent(value, key -> new ValueCandidate(key));
					var source = new ValueSource(analyzer, confidenceScore, sourceDetails);
					candidate.addSource(source);
				};
				
				classAnalyzer.analyze(collector.withAnalyzer(classAnalyzer.getType()), classFilepaths);
				pomAnalyzer.analyze(collector.withAnalyzer(pomAnalyzer.getType()), pomFiles);
				manifestAnalyzer.analyze(collector.withAnalyzer(manifestAnalyzer.getType()), manifest);
				jarNameAnalyzer.analyze(collector.withAnalyzer(jarNameAnalyzer.getType()), jarName);
				
				var sorted = Map.<MavenUidComponent, List<ValueCandidate>>of(
						MavenUidComponent.GROUP_ID, new ArrayList<>(),
						MavenUidComponent.ARTIFACT_ID, new ArrayList<>(),
						MavenUidComponent.VERSION, new ArrayList<>()
						);
				
				var newScoreComparator = Comparator.comparing((ValueCandidate candidate) -> candidate.scoreSum).reversed();
				var sourceComparator = Comparator.comparing((ValueSource source) -> source.score).reversed();
				
				for (var uidComponent : MavenUidComponent.values()) {
					var currentCollected = collected.get(uidComponent);
					var currentSorted = sorted.get(uidComponent);
					
					for (var candidate : currentCollected.values()) {
						currentSorted.add(candidate);
						candidate.sortSources(sourceComparator);
					}
					currentSorted.sort(newScoreComparator);
				}
				
				if (!args.offline) {
					var toCheck = repoChecker.selectCandidatesToCheck(sorted);
					
					var toCheckWithVersion = toCheck.stream().filter(uid -> uid.version != null).collect(Collectors.toSet());
					var toCheckNoVersion = toCheck.stream().filter(uid -> uid.version == null).collect(Collectors.toSet());
					var checkResultsWithVersion = repoChecker.checkOnline(jarHash, toCheckWithVersion);
					var checkResultsNoVersion = repoChecker.searchVersionsAndcheckOnline(jarHash, toCheckNoVersion);
					
					waiting.add(new JarAnalysisWaitingForCompletion(jar, sorted, checkResultsWithVersion, checkResultsNoVersion));
				} else {
					var checkResultsWithVersion = CompletableFuture.completedFuture(Set.<UidCheck>of());
					var checkResultsNoVersion = CompletableFuture.completedFuture(Map.<MavenUid, Set<UidCheck>>of());
					
					waiting.add(new JarAnalysisWaitingForCompletion(jar, sorted, checkResultsWithVersion, checkResultsNoVersion));
				}
				
				jarIndex++;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
	    };
	    
		System.out.println();// end System.out.print with StringUtil.RETURN_LINE
		
	    var onlineCheckInitialized = false;
	    System.out.println("Online-Check initializing...");
	    
	    var jarReports = new ArrayList<JarReport>(waiting.size());
	    
	    // then wait for each jar to finish online analysis to complete analysis
	    for (var jarAnalysis : waiting) {
	    	
	    	// MavenRepoChecker initialization might be finished asynchronously before this point in time,
    		// but we can only start to print after offline analysis is finished to not destroy RETURN_LINE printlns.
	    	if (!onlineCheckInitialized) {
	    		onlineCheckInitialized = true;
	    		
		    	// join to make sure async stuff is done
		    	jarAnalysis.onlineCompletionWithVersion.join();
		    	jarAnalysis.onlineCompletionNoVersion.join();
	    		
	    		System.out.println("Online-Check initialized!");
	    		System.out.println("Online-Check started.");
	    		System.out.println();
	    	}
	    	
	    	var selected = autoSelectCandidate(jarAnalysis);
	    	printer.printResults(jarAnalysis, selected, args.forceDetailedOutput, args.offline);
	    	
	    	if (selected.isEmpty()) {
	    		if (args.interactive) {
		    		var selectedUid = userSelectCandidate(cli, jarAnalysis);
		    		if (selectedUid.isPresent()) {
		    			var jar = jarAnalysis.jar;
		    			selected = Optional.of(new JarReport(jar.name, jar.dir, jar.sha256, null, selectedUid.get()));
		    		}
	    		}
	    	}
	    	selected.ifPresent(jarReports::add);
	    	
	    	printer.printJarEndSeparator();
	    }
	    
	    int total = waiting.size();
	    int skipped = total - jarReports.size();
	    cli.println("Analysis complete (" + skipped + "/" + total + " excluded from report).", LOG::info);
	    
	    if (jarReports.size() >= 1) {
	    	// write report
		    String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
			var reportFile = Paths.get(args.reportFile.replace(AnalysisArgs.DATETIME_SUBSTITUTE, dateTime));
			
			cli.println("Writing report file: " + reportFile.toAbsolutePath(), LOG::info);
		    
		    var generalInfo = new AnalysisInfo(!args.offline, !args.offline ? repoChecker.getRemoteRepos() : List.of());
		    var report = new AnalysisReport(generalInfo, jarReports);
		    var jsonWriter = new ObjectMapper().writerWithDefaultPrettyPrinter();
		    
	    	try {
	    		jsonWriter.writeValue(reportFile.toFile(), report);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
	    } else {
	    	cli.println("Skipping report file because no jars were resolved.", LOG::info);
	    }
		
    	if (!args.offline) {
    		cli.println("Online-Check cleanup started.", LOG::info);
    		repoChecker.shutdown();
    	}
	}
	
	private Optional<JarReport> autoSelectCandidate(JarAnalysisWaitingForCompletion jarAnalysis) {
		var checkResultsWithVersion = jarAnalysis.onlineCompletionWithVersion.join();
    	var checkResultsNoVersion = jarAnalysis.onlineCompletionNoVersion.join();
    	
    	Function<UidCheck, Boolean> onlineMatchToSelect = uid -> uid.matchType.equals(OnlineMatch.FOUND_MATCH_EXACT_SHA);
    	
    	var foundOnline = new ArrayList<UidCheck>(1);
    	for (var uid : checkResultsWithVersion) {
    		if (onlineMatchToSelect.apply(uid)) {
    			foundOnline.add(uid);
    		}
    	}
    	for (var uids : checkResultsNoVersion.values()) {
    		for (var uid : uids) {
        		if (onlineMatchToSelect.apply(uid)) {
        			foundOnline.add(uid);
        		}
    		}
    	}
    	if (foundOnline.size() == 1) {
    		var uid = foundOnline.get(0);
    		var jar = jarAnalysis.jar;
    		return Optional.of(new JarReport(jar.name, jar.dir, jar.sha256, uid.matchType, uid.fullUid));
    	}
    	return Optional.empty();
	}
	
	private Optional<MavenUid> userSelectCandidate(Cli cli, JarAnalysisWaitingForCompletion jarAnalysis) {
		var checkResultsWithVersion = jarAnalysis.onlineCompletionWithVersion.join();
    	var checkResultsNoVersion = jarAnalysis.onlineCompletionNoVersion.join();
		int proposalScoreThreshold = 4;
		var pad = "  ";
		
		BiFunction<UidCheck, MavenUidComponent, Optional<String>> onlineUidProposal = (uid, component) -> {
			String value = uid.fullUid.get(component);
			boolean shouldBeProposed = value != null
					&& (uid.matchType.equals(OnlineMatch.FOUND_MATCH_EXACT_SHA)
					|| uid.matchType.equals(OnlineMatch.FOUND_MATCH_EXACT_CLASSNAMES));
			return shouldBeProposed ? Optional.of(value) : Optional.empty();
		};
		
		System.out.println(pad + "Please complete missing groupId/artifactId/version info for this jar.");
		System.out.println(pad + "Enter the value or enter '<number>!' to select a proposal.");
		
		boolean jarSkipped = false;
		var selectedValues = new ArrayList<String>(3);
		for (var component : List.of(MavenUidComponent.GROUP_ID, MavenUidComponent.ARTIFACT_ID, MavenUidComponent.VERSION)) {
			// not using MavenUidComponent.values() to guarantee order
			
			// collect all proposals
			var proposals = new LinkedHashSet<String>();
			for (var candidate : jarAnalysis.offlineResult.get(component)) {
				if (candidate.scoreSum >= proposalScoreThreshold) {
					proposals.add(candidate.value);
				}
			}
			for (var uid : checkResultsWithVersion) {
				onlineUidProposal.apply(uid, component).ifPresent(proposals::add);
			}
			for (var entry : checkResultsNoVersion.entrySet()) {
				// we add groupId/artifactId pair if found
				if (!component.equals(MavenUidComponent.VERSION)) {
					proposals.add(entry.getKey().get(component));
				}
	    		for (var uid : entry.getValue()) {
	    			onlineUidProposal.apply(uid, component).ifPresent(proposals::add);
	    		}
	    	}
			
			// print proposals
			System.out.println();
			System.out.println(pad + "Enter " + component.xmlTagName + " or select from:");
			var index = 0;
			System.out.println(pad + "    " + index + "! <skip this jar>");
			for (String proposal : proposals) {
				index++;
				System.out.println(pad + "    " + index + "! " + proposal);
			}
			
			// ask user to choose / enter
			boolean hasCorrectInput = false;
			String selected;
			do {
				String inputString = cli.nextLine().trim();
				try {
					if (inputString.endsWith("!")) {
						var selectedIndex = Integer.parseInt(inputString.substring(0, inputString.length() - 1));
						if (selectedIndex == 0) {
							selected = null;
							jarSkipped = true;
							break;
						} else {
							selected = List.copyOf(proposals).get(selectedIndex - 1);
						}
					} else {
						selected = inputString;
					}
				} catch(NumberFormatException e) {
					selected = inputString;
				}
				Pattern pattern = Regex.getPatternForUserInputValidation(component);
				Matcher matcher = pattern.matcher(selected);
				if (matcher.find()) {
					hasCorrectInput = true;
				} else {
					System.out.println("  Given value does not seem to be a valid " + component.xmlTagName + "!");
					System.out.println("  Value must match regex: " + pattern.toString());
				}
			} while (!hasCorrectInput);
			
			if (jarSkipped) {
				break;
			}
			selectedValues.add(selected);
		}
		
		Optional<MavenUid> result;
		if (jarSkipped) {
			System.out.println(pad + "Skipped! Jar '" + jarAnalysis.jar.name + "' will not appear in result report!");
			result = Optional.empty();
		} else {
			var selectedUid = new MavenUid(selectedValues.get(0), selectedValues.get(1), selectedValues.get(2));
			System.out.println();
			System.out.println(pad + "Final values: " + selectedUid);
			System.out.println(pad + "Note that any mistakes can be fixed manually in the report file.");
			result = Optional.of(selectedUid);
		}
		cli.askUserToContinue(pad);
		
		return result;
	}
	
	public static Optional<Manifest> readJarStream(InputStream in, BiFunction<JarEntry, InputStream, Optional<Manifest>> fileConsumer) {
	    try (var jarIn = new JarInputStream(in, false)) {
	    	var manifest = Optional.ofNullable(jarIn.getManifest());
		    JarEntry entry;
			while ((entry = jarIn.getNextJarEntry()) != null) {
				var manualManifest = fileConsumer.apply(entry, jarIn);
				if (manifest.isEmpty() && manualManifest.isPresent()) {
					manifest = manualManifest;
				}
				jarIn.closeEntry();
			}
			return manifest;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
