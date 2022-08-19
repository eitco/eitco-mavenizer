package de.eitco.mavenizer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import de.eitco.mavenizer.AnalyzerReport.JarReport;
import de.eitco.mavenizer.AnalyzerService.JarAnalysisWaitingForCompletion;
import de.eitco.mavenizer.AnalyzerService.MavenUid;
import de.eitco.mavenizer.AnalyzerService.MavenUidComponent;
import de.eitco.mavenizer.AnalyzerService.ValueCandidate;
import de.eitco.mavenizer.MavenRepoChecker.CheckResult;
import de.eitco.mavenizer.MavenRepoChecker.UidCheck;

public class AnalysisResultPrinter {
	
	public void printResults(JarAnalysisWaitingForCompletion jarAnalysis, Optional<JarReport> autoSelected, boolean forceDetailedOutput, boolean offline) {
		
		var checkResultsWithVersion = jarAnalysis.onlineCompletionWithVersion.join();
    	var checkResultsNoVersion = jarAnalysis.onlineCompletionNoVersion.join();
		
    	var offlineResult = jarAnalysis.offlineResult;
		boolean noVersionsFoundOffline = offlineResult.get(MavenUidComponent.VERSION).isEmpty();
		boolean notEnoughInfoFoundOffline =
				offlineResult.get(MavenUidComponent.GROUP_ID).isEmpty()
				|| offlineResult.get(MavenUidComponent.ARTIFACT_ID).isEmpty();
		
		// we expect either only versions being available or only versions missing
    	if (!checkResultsWithVersion.isEmpty() && !checkResultsNoVersion.isEmpty()) {
    		throw new IllegalStateException();
    	}
    	if (noVersionsFoundOffline && !checkResultsWithVersion.isEmpty()) {
    		throw new IllegalStateException();
    	}
    	
    	System.out.println(jarAnalysis.jar.name);
    	
    	Set<UidCheck> onlineResults;
    	if (!noVersionsFoundOffline) {
    		onlineResults = checkResultsWithVersion;
    	} else {
    		onlineResults = checkResultsNoVersion.values().stream()
    				.flatMap(Set::stream)
    				.collect(Collectors.toSet());
    	}
    	
    	// if result is clear, we skip detailed output
    	if (autoSelected.isPresent()) {
    		printAutoSelected(4, autoSelected.get().result, autoSelected.get().onlineCheck);
    		if (forceDetailedOutput) {
    			System.out.println("    Forced details:");
    		} else {
    			return;
    		}
    	}
    	
    	var padding = 8;
    	var pad = " ".repeat(padding);
    	var matchPadding = 16;
    	
		System.out.println();
		System.out.println("    OFFLINE RESULT");
		printOfflineAnalysisResults(jarAnalysis.offlineResult, padding);
		if (!offline) {
			System.out.println();
			System.out.println("    ONLINE RESULT");
			if (onlineResults.size() >= 1) {
				if (!noVersionsFoundOffline) {
					printUidChecks(onlineResults, padding, matchPadding);
				} else {
					System.out.println(pad + "Found artifactId / groupId pairs online, comparing local jar with random online versions:");
					for (var entry : checkResultsNoVersion.entrySet()) {
						System.out.println(pad + "  " + StringUtil.rightPad("PAIR:", matchPadding + 2) + entry.getKey());
						printUidChecks(entry.getValue(), padding + 4, matchPadding);
					}
				}
			} else {
				if (notEnoughInfoFoundOffline) {
					System.out.println(pad + "Did not gather enough information to attempt online search!");
				} else {
					System.out.println(pad + "Did not find any matching artifactId / groupId pair online. Attempt to look for valid versions failed!");
				}
			}
		}
		System.out.println();
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
	
	public void printJarEndSeparator() {
		System.out.println("-".repeat(80));
	}
	
	private void printAutoSelected(int padding, MavenUid selected, CheckResult checkResult) {
		var pad = " ".repeat(padding);
		if (checkResult == null || checkResult.equals(CheckResult.FOUND_NO_MATCH) || checkResult.equals(CheckResult.NOT_FOUND)) {
			System.out.println(pad + "Automatically selected values: " + selected);
		}
		if (checkResult.equals(CheckResult.FOUND_MATCH_EXACT_SHA)) {
			System.out.println(pad + "Found identical jar online with uid: " + selected);
		} else if (checkResult.equals(CheckResult.FOUND_MATCH_EXACT_CLASSNAMES)) {
			// TODO
			System.out.println(pad + "Automatically selected values: " + selected);
		} else if (checkResult.equals(CheckResult.FOUND_MATCH_SUPERSET_CLASSNAMES)) {
			// TODO
			System.out.println(pad + "Automatically selected values: " + selected);
		}
	}
}
