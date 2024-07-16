package de.eitco.mavenizer.analyze.jar;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.lang3.ObjectUtils;

import de.eitco.mavenizer.MavenUid.MavenUidComponent;
import de.eitco.mavenizer.StringUtil;
import de.eitco.mavenizer.analyze.JarAnalyzer.JarAnalyzerType;
import de.eitco.mavenizer.analyze.JarAnalyzer.JarEntry;
import de.eitco.mavenizer.analyze.JarAnalyzer.ValueCandidateCollector;

public class ClassTimestampAnalyzer {
	
	public static final DateTimeFormatter dateToVersion = DateTimeFormatter.ofPattern("yyyy.MM.dd");
	public static final DateTimeFormatter datePrinter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	
	public void analyze(ValueCandidateCollector result, List<JarEntry> classes) {
		
		var datesToOccurence = new HashMap<LocalDate, Integer>();
		var total = 0;
		LocalDate lastChangeDateAllClasses = null;
		
		for (var entry : classes) {
			var lastChange = ObjectUtils.max(entry.timestampCreatedAt, entry.timestampLastModifiedAt);
			if (lastChange != null) {
				var instant = lastChange.toInstant();
				var dateUtc = LocalDate.ofInstant(instant, ZoneOffset.UTC);
				var count = datesToOccurence.getOrDefault(dateUtc, 0);
				datesToOccurence.put(dateUtc, count + 1);
				total++;

				lastChangeDateAllClasses = ObjectUtils.max(lastChangeDateAllClasses, dateUtc);
			}
		}
		
		if (lastChangeDateAllClasses != null) {
			var version = lastChangeDateAllClasses.format(dateToVersion);
			var details = "most recent created/modified date of all classes: " + lastChangeDateAllClasses.format(datePrinter);

			result.addCandidate(MavenUidComponent.VERSION, version, 2, details);
		}

		var highestCountEntry = 
				datesToOccurence.entrySet().stream()
			    .sorted(Entry.<LocalDate, Integer>comparingByValue().reversed())
			    .findFirst();
		
		if (highestCountEntry.isPresent()) {
			var date = highestCountEntry.get().getKey();
			var count = highestCountEntry.get().getValue();
			
			if (count > 1) {
				var version = date.format(dateToVersion);
				int countRatio = (count * 100) / total;
				var countRatioPercent = StringUtil.leftPad(countRatio + "", 3);
				var details = countRatioPercent + "% of classes have created/modified date: " + date.format(datePrinter);
				
				result.addCandidate(MavenUidComponent.VERSION, version, (countRatio > 60 ? 1 : 0), details);
			}

		}
		
	}
	
	public JarAnalyzerType getType() {
		return JarAnalyzerType.CLASS_TIMESTAMP;
	}
}
