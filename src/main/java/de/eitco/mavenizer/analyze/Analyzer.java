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
import de.eitco.mavenizer.analyze.MavenRepoChecker.OnlineMatch;
import de.eitco.mavenizer.analyze.MavenRepoChecker.UidCheck;
import de.eitco.mavenizer.analyze.jar.Helper.Regex;

public class Analyzer {

	private static final Logger LOG = LoggerFactory.getLogger(Analyzer.class);
	
	private static final int VERSION_SEARCH_THRESHOLD = 1;// if no version with higher score is found by offline analysis, random online versions will be tried
	private static final int PROPOSE_CANDIDATE_THRESHOLD = 1;// minimum score needed for a candidate value to be put into list displayed to user for selection
	
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
		
		List<Path> jarPaths = Util.getFiles(args.jars, path -> path.getFileName().toString().toLowerCase().endsWith(".jar"));
		var jarCount = jarPaths.size();
		var jarIndex = 0;
		List<JarAnalysisWaitingForCompletion> waiting = new ArrayList<>(jarCount);
		
		// first we do offline analysis and start online analysis for all jars
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
		    	String absoluteDir = jarPath.toAbsolutePath().normalize().getParent().toString();
		    	Jar jar = new Jar(jarName, absoluteDir, jarHash);
				
				var sorted = jarAnalyzer.analyzeOffline(jar, compressedIn);
				
				if (!args.offline) {
					var toCheck = repoChecker.selectCandidatesToCheck(sorted);
					
					var toCheckWithVersion = toCheck.entrySet().stream()
							.filter(entry -> entry.getKey().version != null)
							.map(Map.Entry::getKey)
							.collect(Collectors.toSet());
					
					var toCheckNoVersion = toCheck.entrySet().stream()
							.filter(entry -> {
								return entry.getKey().version == null
										|| entry.getValue().get(MavenUidComponent.VERSION) <= VERSION_SEARCH_THRESHOLD;// check if score too low
							})
							.map(Map.Entry::getKey)
							.map(uid -> new MavenUid(uid.groupId, uid.artifactId, null))// null out version
							.collect(Collectors.toSet());
					
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
}
