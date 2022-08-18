package de.eitco.mavenizer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import de.eitco.mavenizer.AnalyzerService.JarAnalysisWaitingForCompletion;
import de.eitco.mavenizer.AnalyzerService.MavenUidComponent;
import de.eitco.mavenizer.AnalyzerService.ValueCandidate;
import de.eitco.mavenizer.MavenRepoChecker.CheckResult;
import de.eitco.mavenizer.MavenRepoChecker.UidCheck;

public class AnalysisResultPrinter {
	
	public void printResults(JarAnalysisWaitingForCompletion jarAnalysis, boolean forceDetailedOutput) {
		
		var checkResultsWithVersion = jarAnalysis.onlineCompletionWithVersion.join();
    	var checkResultsNoVersion = jarAnalysis.onlineCompletionNoVersion.join();
		
		boolean versionsFoundOffline = !jarAnalysis.offlineResult.get(MavenUidComponent.VERSION).isEmpty();
		
		// we expect either only versions being available or only versions missing
    	if (!checkResultsWithVersion.isEmpty() && !checkResultsNoVersion.isEmpty()) {
    		throw new IllegalStateException();
    	}
    	if (!versionsFoundOffline && !checkResultsWithVersion.isEmpty()) {
    		throw new IllegalStateException();
    	}
    	
    	System.out.println(jarAnalysis.jar.name);
    	
    	Set<UidCheck> onlineResults;
    	if (versionsFoundOffline) {
    		onlineResults = checkResultsWithVersion;
    	} else {
    		onlineResults = checkResultsNoVersion.values().stream()
    				.flatMap(Set::stream)
    				.collect(Collectors.toSet());
    	}
    	
    	boolean identicalJarFoundOnline = false;
    	if (onlineResults.size() == 1) {
    		var result = onlineResults.iterator().next();
    		identicalJarFoundOnline = result.checkResult.equals(CheckResult.MATCH_EXACT_SHA);
    	}
    	
    	// if result is clear, we skip detailed output
    	if (identicalJarFoundOnline && !forceDetailedOutput) {
    		System.out.println("    Found identical jar online with uid: " + onlineResults.iterator().next().fullUid);
			System.out.println("-".repeat(80));
			return;
    	}
    	
    	var padding = 8;
    	var pad = " ".repeat(padding);
    	var matchPadding = 16;
    	
		System.out.println();
		System.out.println("    OFFLINE RESULT");
		printOfflineAnalysisResults(jarAnalysis.offlineResult, padding);
		System.out.println();
		System.out.println("    ONLINE RESULT");
		if (identicalJarFoundOnline && forceDetailedOutput) {
			System.out.println("      Found identical jar online with uid: " + onlineResults.iterator().next().fullUid);
			System.out.println("      Details:");
		}
		if (onlineResults.size() >= 1) {
			if (versionsFoundOffline) {
				printUidChecks(onlineResults, padding, matchPadding);
			} else {
				System.out.println(pad + "Found artifactId / groupId pairs online, comparing local jar with random online versions:");
				for (var entry : checkResultsNoVersion.entrySet()) {
					System.out.println(pad + "  " + StringUtil.rightPad("PAIR:", matchPadding + 2) + entry.getKey());
					printUidChecks(entry.getValue(), padding + 4, matchPadding);
				}
			}
		} else {
			if (versionsFoundOffline) {
				System.out.println(pad + "?? 1");
			} else {
				System.out.println(pad + "Did not find any matching artifactId / groupId pair online. Attempt to look for valid versions failed!");
			}
		}
		System.out.println();
		System.out.println("-".repeat(80));
	}
	
	public void printUidChecks(Set<UidCheck> checkedUids, int padding, int matchPadding) {
		var pad = " ".repeat(padding);
		for (var uidCheck : checkedUids) {
			System.out.println(pad + StringUtil.leftPad(uidCheck.checkResult.name() + " FOR ", matchPadding)  + uidCheck.fullUid);
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
}
