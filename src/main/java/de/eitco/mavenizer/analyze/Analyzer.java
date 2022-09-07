package de.eitco.mavenizer.analyze;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
import de.eitco.mavenizer.analyze.JarAnalyzer.ManifestFile;
import de.eitco.mavenizer.analyze.MavenRepoChecker.UidCheck;
import de.eitco.mavenizer.analyze.jar.Helper.Regex;

public class Analyzer {

	private static final Logger LOG = LoggerFactory.getLogger(Analyzer.class);
	
	private static final int VERSION_SEARCH_MAX_THRESHOLD = 1;// if no version with higher score is found by offline analysis, random online versions will be tried
	private static final int VERSION_NO_SEARCH_THRESHOLD = 5;// if there is a version with this score or higher, random  online version search will not be tried
	private static final int PROPOSE_CANDIDATE_THRESHOLD = 1;// minimum score needed for a candidate value to be put into list displayed to user for selection
	
	public static class Jar {
		public final String name;
		public final String dir;
		public final JarHashes hashes;
		
		public Jar(String name, String dir, JarHashes hashes) {
			this.name = name;
			this.dir = dir;
			this.hashes = hashes;
		}
	}
	
	public static class JarHashes {
		public final String jarSha256;
		public final Map<Path, byte[]> classesToSha256;// since we do not print class hashes, we can keep them as byte array
		
		public JarHashes(String jarSha256, Map<Path, byte[]> classesToSha256) {
			this.jarSha256 = jarSha256;
			this.classesToSha256 = classesToSha256;
		}
	}
	
	public static class JarAnalysisResult {
		public final Optional<ManifestFile> manifestFile;
		public final Map<MavenUidComponent, List<ValueCandidate>> sortedValueCandidates;
		
		public JarAnalysisResult(Optional<ManifestFile> manifestFile, Map<MavenUidComponent, List<ValueCandidate>> sortedValueCandidates) {
			this.manifestFile = manifestFile;
			this.sortedValueCandidates = sortedValueCandidates;
		}
	}
	
	public static class JarAnalysisWaitingForCompletion {
		public final Jar jar;
		public final JarAnalysisResult offlineResult;
		public final CompletableFuture<Set<UidCheck>> onlineCompletionWithVersion;
		public final CompletableFuture<Map<MavenUid, Set<UidCheck>>> onlineCompletionNoVersion;
		
		public JarAnalysisWaitingForCompletion(Jar jar, JarAnalysisResult offlineResult,
				CompletableFuture<Set<UidCheck>> onlineCompletionWithVersion,
				CompletableFuture<Map<MavenUid, Set<UidCheck>>> onlineCompletionNoVersion) {
			this.jar = jar;
			this.offlineResult = offlineResult;
			this.onlineCompletionWithVersion = onlineCompletionWithVersion;
			this.onlineCompletionNoVersion = onlineCompletionNoVersion;
		}
	}
	
	public static class UserSelectionResult {
		Optional<MavenUid> selected;
		boolean wantsToExit;
		
		public UserSelectionResult(Optional<MavenUid> selected, boolean wantsToExit) {
			this.selected = selected;
			this.wantsToExit = wantsToExit;
		}
	}
	
	public static final String COMMAND_NAME = "analyze";
	
	private final AnalysisArgs args = new AnalysisArgs();
	private final JarAnalyzer jarAnalyzer = new JarAnalyzer();
	private final ConsolePrinter printer = new ConsolePrinter();
	
	private MavenRepoChecker repoChecker = null;
	
	public void addCommand(Cli cli) {
		cli.addCommand(COMMAND_NAME, args);
	}
	
	public void runAnalysis(Cli cli) {
		
		cli.validateArgsOrRetry(COMMAND_NAME, () -> {
			var errors = List.of(
					args.validateJars(),
					args.validateReportFile(),
					args.validateStartNumber()
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
		
		List<Path> jarPaths = Util.getFiles(args.jars, path -> path.getFileName().toString().toLowerCase().endsWith(".jar"));
		var jarCount = jarPaths.size();
		var jarIndex = 0;
		List<JarAnalysisWaitingForCompletion> waiting = new ArrayList<>(jarCount);
		
		// first we do offline analysis and start online analysis for all jars
	    for (var jarPath : jarPaths) {
	    	
	    	if ((args.start - 1) > jarIndex) {
	    		jarIndex++;
	    		continue;
	    	}
	    	
	    	if (args.limit >= 0 && jarIndex >= (args.start - 1 + args.limit)) {
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
		    	JarHashes jarHashes = Util.sha256(unzipIn);
		    	String absoluteDir = jarPath.toAbsolutePath().normalize().getParent().toString();
		    	Jar jar = new Jar(jarName, absoluteDir, jarHashes);
				
				var jarAnalysisResult = jarAnalyzer.analyzeOffline(jar, compressedIn);
				var sorted = jarAnalysisResult.sortedValueCandidates;
				
				if (!args.offline) {
					var toCheck = repoChecker.selectCandidatesToCheck(sorted);
					
					int highestVersionScore = toCheck.entrySet().stream()
							.filter(entry -> entry.getKey().version != null)
							.mapToInt(entry -> entry.getValue().get(MavenUidComponent.VERSION))
							.max().orElse(0);
					
					var toCheckWithVersion = toCheck.entrySet().stream()
							.filter(entry -> entry.getKey().version != null)
							.map(Map.Entry::getKey)
							.collect(Collectors.toSet());
					
					var toCheckNoVersion = toCheck.entrySet().stream()
							.filter(entry -> {
								if (entry.getKey().version == null) {
									return true;
								} else {
									int versionScore = entry.getValue().get(MavenUidComponent.VERSION);
									// if score too low and no other good version candidates
									return highestVersionScore <= VERSION_NO_SEARCH_THRESHOLD && versionScore <= VERSION_SEARCH_MAX_THRESHOLD;
								}
							})
							.map(Map.Entry::getKey)
							.map(uid -> new MavenUid(uid.groupId, uid.artifactId, null))// null out version
							.collect(Collectors.toSet());
					
					var checkResultsWithVersion = repoChecker.checkOnline(jarHashes, toCheckWithVersion);
					var checkResultsNoVersion = repoChecker.searchVersionsAndcheckOnline(jarHashes, toCheckNoVersion);
					
					waiting.add(new JarAnalysisWaitingForCompletion(jar, jarAnalysisResult, checkResultsWithVersion, checkResultsNoVersion));
				} else {
					var checkResultsWithVersion = CompletableFuture.completedFuture(Set.<UidCheck>of());
					var checkResultsNoVersion = CompletableFuture.completedFuture(Map.<MavenUid, Set<UidCheck>>of());
					
					waiting.add(new JarAnalysisWaitingForCompletion(jar, jarAnalysisResult, checkResultsWithVersion, checkResultsNoVersion));
				}
				
				jarIndex++;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
	    };
	    
		System.out.println();// end System.out.print with StringUtil.RETURN_LINE
		
	    var onlineCheckInitialized = false;
	    System.out.println("Online-Check initializing...");
	    
	    var count = 1;
	    var jarReportFutures = new ArrayList<CompletableFuture<JarReport>>(waiting.size());
	    
	    boolean userExit = false;
	    
	    // then wait for each jar to finish online analysis to complete analysis
	    for (var jarAnalysis : waiting) {
	    	var jar = jarAnalysis.jar;
	    	
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
	    	
	    	var autoSelected = autoSelectCandidate(jarAnalysis);
	    	Optional<JarReport> selected = autoSelected.map(uid -> new JarReport(jar.name, jar.dir, jar.hashes.jarSha256, true, uid.fullUid));
	    	CompletableFuture<JarReport> selectedToBeChecked = null;
	    	
	    	System.out.println(jarAnalysis.jar.name + " (" + count + "/" + waiting.size() + ")");
	    	printer.printResults(jarAnalysis, autoSelected, args.forceDetailedOutput, args.offline);
	    	
	    	if (selected.isEmpty()) {
	    		if (args.interactive) {
	    			var userResult = userSelectCandidate(cli, jarAnalysis);
	    			if (userResult.wantsToExit) {
	    				userExit = true;
	    				break;
	    			}
		    		var userSelectedUid = userResult.selected;
		    		if (userSelectedUid.isPresent()) {
		    			
		    			// we check again to see if user has provided a UID of a jar that is available online and identical, using local remote cache if possible
		    			if (!args.offline) {
		    				var checkedSet = repoChecker.check(jar.hashes, Set.of(userSelectedUid.get()), true);
		    				selectedToBeChecked = checkedSet.thenApply(set -> {
		    					var uidCheck = set.iterator().next();
		    					var foundOnRemote = uidCheck.matchType.isConsideredIdentical();
		    					return new JarReport(jar.name, jar.dir, jar.hashes.jarSha256, foundOnRemote, userSelectedUid.get());
		    				});
		    			} else {
		    				selected = Optional.of(new JarReport(jar.name, jar.dir, jar.hashes.jarSha256, false, userSelectedUid.get()));
		    			}
		    			
		    		}
	    		}
	    	}
	    	if (selected.isPresent()) {
	    		jarReportFutures.add(CompletableFuture.completedFuture(selected.get()));
	    	} else if (selectedToBeChecked != null) {
	    		jarReportFutures.add(selectedToBeChecked);
	    	}
	    	
	    	printer.printJarEndSeparator();
	    	count++;
	    }
	    
	    if (!userExit) {
	    	int total = waiting.size();
	 	    int skipped = total - jarReportFutures.size();
	 	    cli.println("Analysis complete (" + skipped + "/" + total + " excluded from report).", LOG::info);
	 	    
	 	    if (jarReportFutures.size() >= 1) {
	 	    	// write report
	 		    String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
	 			var reportFile = Paths.get(args.reportFile.replace(AnalysisArgs.DATETIME_SUBSTITUTE, dateTime));
	 			
	 			cli.println("Writing report file: " + reportFile.toAbsolutePath(), LOG::info);
	 		    
	 		    var generalInfo = new AnalysisInfo(!args.offline, !args.offline ? repoChecker.getRemoteRepos() : List.of());
	 		    var jarReports = jarReportFutures.stream()
	 		    		.map(CompletableFuture::join)
	 		    		.collect(Collectors.toList());
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
	    } else {
	    	cli.println("Skipping report file because user exited.", LOG::info);
	    }
		
    	if (!args.offline) {
    		cli.println("Online-Check cleanup started.", LOG::info);
    		repoChecker.shutdown();
    	}
	}
	
	private Optional<UidCheck> autoSelectCandidate(JarAnalysisWaitingForCompletion jarAnalysis) {
		var checkResultsWithVersion = jarAnalysis.onlineCompletionWithVersion.join();
    	var checkResultsNoVersion = jarAnalysis.onlineCompletionNoVersion.join();
    	
    	Function<UidCheck, Boolean> onlineMatchToSelect = uid -> uid.matchType.isConsideredIdentical();
    	
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
    	if (foundOnline.size() >= 1) {
    		return Optional.of(foundOnline.get(0));
    	}
    	return Optional.empty();
	}
	
	private UserSelectionResult userSelectCandidate(Cli cli, JarAnalysisWaitingForCompletion jarAnalysis) {
		var checkResultsWithVersion = jarAnalysis.onlineCompletionWithVersion.join();
    	var checkResultsNoVersion = jarAnalysis.onlineCompletionNoVersion.join();
    	var manifest = jarAnalysis.offlineResult.manifestFile;
    	
		var pad = "  ";
		var optionPad = pad + "    ";
		
		BiFunction<UidCheck, MavenUidComponent, Optional<String>> onlineUidProposal = (uid, component) -> {
			String value = uid.fullUid.get(component);
			boolean shouldBeProposed = value != null && (uid.matchType.isConsideredIdentical());
			return shouldBeProposed ? Optional.of(value) : Optional.empty();
		};
		
		System.out.println(pad + "Please complete missing groupId/artifactId/version info for this jar.");
		System.out.println(pad + "The following commands are always available:");
		System.out.println(optionPad + "exit! <exit>");
		System.out.println(optionPad + "s!    <skip this jar>");
		if (manifest.isPresent()) {
			System.out.println(optionPad + "m!    <show manifest>");
		}
		
		boolean jarSkipped = false;
		boolean exit = false;
		
		var selectedValues = new ArrayList<String>(3);
		for (var component : List.of(MavenUidComponent.GROUP_ID, MavenUidComponent.ARTIFACT_ID, MavenUidComponent.VERSION)) {
			// not using MavenUidComponent.values() to guarantee order
			
			// collect all proposals
			var proposals = new LinkedHashSet<String>();
			for (var candidate : jarAnalysis.offlineResult.sortedValueCandidates.get(component)) {
				if (candidate.scoreSum >= PROPOSE_CANDIDATE_THRESHOLD) {
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
			System.out.println(pad + "Enter " + component.xmlTagName + " directly" + (proposals.isEmpty() ? "" : " or select from") + ":");
			var index = 1;
			for (String proposal : proposals) {
				System.out.println(optionPad + index + "! " + proposal);
				index++;
			}
			
			// ask user to choose / enter
			boolean hasCorrectInput = false;
			String selected = null;
			do {
				String inputString = cli.nextLine().trim();
				try {
					if (inputString.endsWith("!")) {
						if (inputString.equals("exit!")) {
							exit = true;
							break;
						} else if (inputString.equals("s!")) {
							jarSkipped = true;
							break;
						} else if (manifest.isPresent() && inputString.equals("m!")) {
							System.out.println();
							System.out.println(manifest.get().fileAsString);
							System.out.println();
							cli.askUserToContinue(pad, "Press Enter to continue choosing a " + component.xmlTagName + "..." );
						} else {
							var selectedIndex = Integer.parseInt(inputString.substring(0, inputString.length() - 1));
							if (selectedIndex >= 1 && selectedIndex <= proposals.size()) {
								selected = List.copyOf(proposals).get(selectedIndex - 1);
							} else {
								System.out.println(pad + "Given selection is not in valid range 1 - " + proposals.size() + "!");
							}
						}
					} else {
						selected = inputString;
					}
				} catch(NumberFormatException e) {
					selected = inputString;
				}
				if (selected != null) {
					if (selected.isEmpty()) {
						System.out.println(pad + "Empty! Please enter the value directly or enter '<number>!' to select a proposal from above.");
					} else {
						Pattern pattern = Regex.getPatternForUserInputValidation(component);
						Matcher matcher = pattern.matcher(selected);
						if (matcher.find()) {
							hasCorrectInput = true;
						} else {
							System.out.println(pad + "Given value does not seem to be a valid " + component.xmlTagName + "!");
							System.out.println(pad + "Value must match regex: " + pattern.toString());
						}
					}
				}
			} while (!hasCorrectInput);
			
			if (jarSkipped || exit) {
				break;
			}
			selectedValues.add(selected);
		}
		
		Optional<MavenUid> result;
		if (jarSkipped || exit) {
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
		
		return new UserSelectionResult(result, exit);
	}
}
