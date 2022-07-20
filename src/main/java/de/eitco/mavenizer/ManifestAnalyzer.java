package de.eitco.mavenizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ManifestAnalyzer {

	public static enum Attribute {
		Extension_Name,
		Implementation_Title,
		Implementation_Vendor_Id,
		Automatic_Module_Name,
		Bundle_SymbolicName,
		Main_Class;
		
		@Override
		public String toString() {
			return this.name().replace('_', '-');
		}
		public static Attribute fromString(String attributeName) {
			return Attribute.valueOf(attributeName.replace('-', '_'));
		}
	}
	
	@FunctionalInterface
	public static interface ValueCandidatesExtractor {
		public List<Candidate> getCandidates(String attributeValue);
	}
	
	public static class Candidate {
		public final String value;
		public final int confidence;
		public Candidate(String value, int confidence) {
			this.value = value;
			this.confidence = confidence;
		}
		@Override
		public String toString() {
			return "[" + confidence + ", " + value + "]";
		}
	}
	
	public static Map<Attribute, ValueCandidatesExtractor> groupIdExtractors = Map.of(
			Attribute.Extension_Name,
			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 2, 1),
			Attribute.Implementation_Title,
			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 2, 1),
			Attribute.Implementation_Vendor_Id,
			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 4, 2)
//			Attribute.Automatic_Module_Name,
//			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 0, 0),
//			Attribute.Bundle_SymbolicName,
//			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 0, 0),
//			Attribute.Main_Class,
//			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 0, 0)
	);
	
	private static List<Candidate> extractPattern_PackageWithOptionalClass(String attributeValue, int confidenceExactMatch, int confidenceSubpatternMatch) {
		
		Matcher matcher = Regex.packageWithOptionalClass.matcher(attributeValue);
		if (!matcher.find()) {
			return List.of();
		}
		String pakkage = matcher.group(Regex.GROUP_ID_PACKAGE);
		
		if (pakkage == null) {
			return List.of();
		} else {
			var candidates = CandidateExtractionHelper.getPackageCandidates(pakkage);
			var result = new ArrayList<Candidate>(candidates.size());
			for (String candidate : candidates) {
				int confidence = candidate.equals(attributeValue) ? confidenceExactMatch : confidenceSubpatternMatch;
				result.add(new Candidate(candidate, confidence));
			}
			return result;
		}
	}
	
	private static class Regex {
		
		public final static String GROUP_ID_PACKAGE = "package";
		public final static String GROUP_ID_CLASS = "class";
		
		public final static String PATTERN_PACKAGE = "[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)+";
		public final static String PATTERN_CLASS = "[A-Z]\\w*";

		public final static String PATTERN_PACKAGE_WITH_OPTIONAL_CLASS =
				"(?<" + GROUP_ID_PACKAGE + ">" + PATTERN_PACKAGE + ")(\\.(?<" + GROUP_ID_CLASS + ">" + PATTERN_CLASS + "))?";
		
		public final static Pattern packageWithOptionalClass = Pattern.compile(PATTERN_PACKAGE_WITH_OPTIONAL_CLASS);
		
	}
	
	public static class CandidateExtractionHelper {
		
		/**
		 * Returns the first 2, 3 or 4 parts of the given package name.
		 */
		public static List<String> getPackageCandidates(String pakkage) {
			String[] parts = pakkage.split("\\.");
			if (parts.length < 2) {
				return List.of();
			}
			var result = new ArrayList<String>(3);
			result.add(parts[0] + "." + parts[1]);
			if (parts.length > 2) {
				result.add(parts[0] + "." + parts[1] + "." + parts[2]);
				if (parts.length > 3) {
					result.add(parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3]);
				}
			}
			return result;
		}
	}
}
