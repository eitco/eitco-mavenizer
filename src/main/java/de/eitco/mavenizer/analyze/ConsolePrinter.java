package de.eitco.mavenizer.analyze;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import de.eitco.mavenizer.AnalysisReport.JarReport;
import de.eitco.mavenizer.MavenUid;
import de.eitco.mavenizer.MavenUid.MavenUidComponent;
import de.eitco.mavenizer.StringUtil;
import de.eitco.mavenizer.analyze.Analyzer.JarAnalysisWaitingForCompletion;
import de.eitco.mavenizer.analyze.MavenRepoChecker.OnlineMatch;
import de.eitco.mavenizer.analyze.MavenRepoChecker.UidCheck;

public class ConsolePrinter {
	
	public void printResults(JarAnalysisWaitingForCompletion jarAnalysis, Optional<JarReport> autoSelected, boolean forceDetailedOutput, boolean offline) {
		
		var checkResultsWithVersion = jarAnalysis.onlineCompletionWithVersion.join();
    	var checkResultsNoVersion = jarAnalysis.onlineCompletionNoVersion.join();
    	int totalOnlineCheckCount = checkResultsWithVersion.size() + checkResultsNoVersion.size();
    	
    	System.out.println(jarAnalysis.jar.name);
    	
    	// if result is clear, we skip detailed output
    	if (autoSelected.isPresent()) {
    		printAutoSelected(4, autoSelected.get().result, autoSelected.get().onlineCheck);
    		if (forceDetailedOutput) {
    			System.out.println("    Forced details:");
    		} else {
    			return;
    		}
    	}
    	System.out.println();
    	System.out.println("    SHA_256 (uncompressed): " + jarAnalysis.jar.hashes.jarSha256);
    	
    	var padding = 8;
    	var pad = " ".repeat(padding);
    	var matchPadding = 16;
    	
		System.out.println();
		System.out.println("    OFFLINE RESULT");
		printOfflineAnalysisResults(jarAnalysis.offlineResult.sortedValueCandidates, padding);
		if (!offline) {
			System.out.println();
			System.out.println("    ONLINE RESULT");
			if (totalOnlineCheckCount >= 1) {
				
				// print normal download attempts
				printUidChecks(checkResultsWithVersion, padding + 2, matchPadding);
				
				// print version search + download attempts
				if (checkResultsNoVersion.size() >= 1) {
					System.out.println(pad + "Found artifactId / groupId pairs online, comparing local jar with random online versions:");
					for (var entry : checkResultsNoVersion.entrySet()) {
						System.out.println(pad + "  " + StringUtil.rightPad("PAIR:", matchPadding + 2) + entry.getKey());
						printUidChecks(entry.getValue(), padding + 4, matchPadding);
					}
				}
				
			} else {
				System.out.println(pad + "Did not gather enough information to attempt online search!");
			}
		}
		System.out.println();
	}
	
	public void printUidChecks(Set<UidCheck> checkedUids, int padding, int matchPadding) {
		var pad = " ".repeat(padding);
		for (var uidCheck : checkedUids) {
			var url = uidCheck.url.isPresent() ? (" AT " + uidCheck.url.get()) : "";
			System.out.println(pad + StringUtil.leftPad(uidCheck.matchType.name() + "   FOR ", matchPadding)  + uidCheck.fullUid + url);
		}
	}
	
	public void printOfflineAnalysisResults(Map<MavenUidComponent, List<ValueCandidate>> result, int padding) {
		BiFunction<String, Integer, String> scoredValueToString =
				(value, score) -> StringUtil.leftPad(score + "", 2) + " | " + value;
				
		var pad = " ".repeat(padding);
		for (var uidComponent : MavenUidComponent.values()) {
			
			var resultList = result.get(uidComponent);
			
			if (resultList.size() > 0) {
				System.out.println(pad + uidComponent.name());
			}
			int valuePadding = 20;
			for (ValueCandidate candidate : resultList) {
				var valueString = scoredValueToString.apply(candidate.value, candidate.scoreSum);
				
				int valueLength = valueString.toString().length();
				valuePadding = Math.max(valuePadding, valueLength);
			}
			for (ValueCandidate candidate : resultList) {
				var valueAndScore = scoredValueToString.apply(candidate.value, candidate.scoreSum);
				
				for (int i = 0; i < candidate.sources.size(); i++) {
					var source = candidate.sources.get(i);
					var valueString = pad + "    " + StringUtil.rightPad(i == 0 ? valueAndScore : "", valuePadding + 2);
					var sourceString = " (" + source.score + " | " + source.analyzer.displayName + " -> " + source.details + ")";
					System.out.println(valueString + sourceString);
				}
			}
		}
	}
	
	public void printJarEndSeparator() {
		System.out.println("-".repeat(80));
	}
	
	private void printAutoSelected(int padding, MavenUid selected, OnlineMatch matchType) {
		var pad = " ".repeat(padding);
		if (matchType == null || matchType.equals(OnlineMatch.FOUND_NO_MATCH) || matchType.equals(OnlineMatch.NOT_FOUND)) {
			System.out.println(pad + "Automatically selected values: " + selected);
		}
		if (matchType.equals(OnlineMatch.FOUND_MATCH_EXACT_SHA)) {
			System.out.println(pad + "Found identical jar online, UID: " + selected);
		} else if (matchType.equals(OnlineMatch.FOUND_MATCH_EXACT_CLASSES_SHA)) {
			System.out.println(pad + "Found not fully identical jar with identical classes online, UID: " + selected);
		} else if (matchType.equals(OnlineMatch.FOUND_MATCH_SUPERSET_CLASSNAMES)) {
			// TODO
			System.out.println(pad + "Automatically selected values: " + selected);
		}
	}
}
