package de.eitco.mavenizer.analyze;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import de.eitco.mavenizer.analyze.Analyzer.FileAnalyzer;

public class ValueCandidate {
	
	public static class ValueSource {
		public final FileAnalyzer analyzer;
		public final int score;
		public final String details;
		
		public ValueSource(FileAnalyzer analyzer, int score, String details) {
			this.analyzer = analyzer;
			this.score = score;
			this.details = details;
		}
	}
	
	public final String value;
	public final List<ValueSource> sources;
	public int scoreSum = 0;
	
	private final List<ValueSource> sourcesInternal = new ArrayList<>();
	
	public ValueCandidate(String value) {
		this.value = value;
		this.sources = Collections.unmodifiableList(sourcesInternal);
	}
	public void addSource(ValueSource source) {
		sourcesInternal.add(source);
		scoreSum += source.score;
	}
	public void sortSources(Comparator<? super ValueSource> comparator) {
		sourcesInternal.sort(comparator);
	}
}