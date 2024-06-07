package de.eitco.mavenizer.analyze;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import de.eitco.mavenizer.Cli;
import de.eitco.mavenizer.MavenUid;
import de.eitco.mavenizer.MavenUid.MavenUidComponent;
import de.eitco.mavenizer.StringUtil;
import de.eitco.mavenizer.analyze.Analyzer.JarAnalysisWaitingForCompletion;
import de.eitco.mavenizer.analyze.OnlineAnalyzer.OnlineMatch;
import de.eitco.mavenizer.analyze.OnlineAnalyzer.UidCheck;

public class ConsolePrinter {

	private final Cli cli;
	public ConsolePrinter(Cli cli) {
		this.cli = cli;
	}

	public void printResults(JarAnalysisWaitingForCompletion jarAnalysis, Optional<UidCheck> autoSelected, boolean forceDetailedOutput, boolean offline) {
		
		var checkResultsWithVersion = jarAnalysis.onlineCompletionWithVersion.join();
    	var checkResultsNoVersion = jarAnalysis.onlineCompletionNoVersion.join();
    	int totalOnlineCheckCount = checkResultsWithVersion.size() + checkResultsNoVersion.size();
    	
    	// if result is clear, we skip detailed output
    	if (autoSelected.isPresent()) {
    		printAutoSelected(4, autoSelected.get().fullUid, autoSelected.get().matchType);
    		if (forceDetailedOutput) {
    			cli.println("    Forced details:");
    		} else {
    			return;
    		}
    	}
    	cli.println();
    	cli.println("    SHA_256 (uncompressed): " + jarAnalysis.jar.hashes.jarSha256);
    	if (jarAnalysis.offlineResult.manifestFile.isEmpty()) {
    		cli.println("    WARNING: Jar is missing 'META-INF/MANIFEST.MF'!");
    	}
    	
    	var padding = 8;
    	var pad = " ".repeat(padding);
    	var matchPadding = 16;
    	
		cli.println();
		cli.println("    OFFLINE RESULT");
		printOfflineAnalysisResults(jarAnalysis.offlineResult.sortedValueCandidates, padding);
		if (!offline) {
			cli.println();
			cli.println("    ONLINE RESULT");
			if (totalOnlineCheckCount >= 1) {
				
				// print normal download attempts
				printUidChecks(checkResultsWithVersion, padding + 2, matchPadding);
				
				// print version search + download attempts
				if (checkResultsNoVersion.size() >= 1) {
					cli.println(pad + "Found artifactId / groupId pairs online, comparing local jar with random online versions:");
					for (var entry : checkResultsNoVersion.entrySet()) {
						cli.println(pad + "  " + StringUtil.rightPad("PAIR:", matchPadding + 2) + entry.getKey());
						printUidChecks(entry.getValue(), padding + 4, matchPadding);
					}
				}
				
			} else {
				cli.println(pad + "Did not gather enough information to attempt online search!");
			}
		}
		cli.println();
	}
	
	public void printUidChecks(Set<UidCheck> checkedUids, int padding, int matchPadding) {
		var pad = " ".repeat(padding);
		for (var uidCheck : checkedUids) {
			var url = uidCheck.url.isPresent() ? (" AT " + uidCheck.url.get()) : "";
			cli.println(pad + StringUtil.leftPad(uidCheck.matchType.name() + "   FOR ", matchPadding)  + uidCheck.fullUid + url);
		}
	}
	
	public void printOfflineAnalysisResults(Map<MavenUidComponent, List<ValueCandidate>> result, int padding) {
		BiFunction<String, Integer, String> scoredValueToString =
				(value, score) -> StringUtil.leftPad(score + "", 2) + " | " + value;
				
		var pad = " ".repeat(padding);
		for (var uidComponent : MavenUidComponent.values()) {
			
			var resultList = result.get(uidComponent);
			
			if (resultList.size() > 0) {
				cli.println(pad + uidComponent.name());
			}
			int valuePadding = 20;
			for (ValueCandidate candidate : resultList) {
				var valueString = scoredValueToString.apply(candidate.value, candidate.getScoreSum());
				
				int valueLength = valueString.length();
				valuePadding = Math.max(valuePadding, valueLength);
			}
			for (ValueCandidate candidate : resultList) {
				var valueAndScore = scoredValueToString.apply(candidate.value, candidate.getScoreSum());
				
				for (int i = 0; i < candidate.sources.size(); i++) {
					var source = candidate.sources.get(i);
					var valueString = pad + "    " + StringUtil.rightPad(i == 0 ? valueAndScore : "", valuePadding + 2);
					var sourceString = " (" + source.score + " | " + source.analyzer.displayName + " -> " + source.details + ")";
					cli.println(valueString + sourceString);
				}
			}
		}
	}
	
	public void printJarEndSeparator() {
		cli.println("-".repeat(80));
	}
	
	private void printAutoSelected(int padding, MavenUid selected, OnlineMatch matchType) {
		var pad = " ".repeat(padding);
		if (matchType == null || matchType.equals(OnlineMatch.FOUND_NO_MATCH) || matchType.equals(OnlineMatch.NOT_FOUND)) {
			cli.println(pad + "Automatically selected values: " + selected);
		} else if (matchType.equals(OnlineMatch.FOUND_MATCH_EXACT_SHA)) {
			cli.println(pad + "Found identical jar online, UID: " + selected);
		} else if (matchType.equals(OnlineMatch.FOUND_MATCH_EXACT_CLASSES_SHA)) {
			cli.println(pad + "Found not fully identical jar with identical classes online, UID: " + selected);
		} else if (matchType.equals(OnlineMatch.FOUND_MATCH_SUPERSET_CLASSNAMES)) {
			// TODO
			cli.println(pad + "Automatically selected values: " + selected);
		}
	}
}
