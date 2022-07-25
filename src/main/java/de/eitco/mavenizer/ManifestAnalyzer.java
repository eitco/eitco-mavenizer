package de.eitco.mavenizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import de.eitco.mavenizer.Main.Analyzer;
import de.eitco.mavenizer.Main.MavenUidComponent;
import de.eitco.mavenizer.Main.ScoredValueSource;
import de.eitco.mavenizer.Main.ValueCandidate;

public class ManifestAnalyzer {
	
	public Map<MavenUidComponent, List<ValueCandidate>> analyze(Manifest manifest) {

		var result = Map.<MavenUidComponent, List<ValueCandidate>>of(
				MavenUidComponent.GROUP_ID, new ArrayList<ValueCandidate>(),
				MavenUidComponent.ARTIFACT_ID, new ArrayList<ValueCandidate>(),
				MavenUidComponent.VERSION, new ArrayList<ValueCandidate>()
				);
		
		for (var entry : manifest.getMainAttributes().entrySet()) {
			String attrName = ((Attributes.Name) entry.getKey()).toString();
			if (Attribute.stringValues.contains(attrName)) {
				
				var attr = Attribute.fromString(attrName);
				var attrValue = (String) entry.getValue();
				var attrEntry = new ManifestEntry(attr, attrValue);
				
				for (var uidComponent : MavenUidComponent.values()) {
					var resultList = result.get(uidComponent);
					Map<Attribute, ValueCandidatesExtractor> extractors = null;
					
					switch (uidComponent) {
					case GROUP_ID:
						extractors = groupIdExtractors;
						break;
					case ARTIFACT_ID:
						extractors = artifactIdExtractors;
						break;
					case VERSION:
						extractors = Map.of();
						break;
					}
					
					var extractor = extractors.get(attr);
					if (extractor != null) {
						var candidates = extractor.getCandidates(attrValue);
						for (ScoredValue value : candidates) {
							var resultValue = new ValueCandidate(value, Analyzer.MANIFEST, attrEntry);
							resultList.add(resultValue);
						}
					}
				}
			}
		}
		
		return result;
	}
	
	public static class ManifestEntry implements ScoredValueSource {
		private final Attribute attr;
		private final String attrValue;
		
		public ManifestEntry(Attribute attr, String attrValue) {
			this.attr = attr;
			this.attrValue = attrValue;
		}
		@Override
		public String displaySource() {
			return attr.toString() + ": '" + attrValue + "'";
		}
	}

	public static enum Attribute {
		Extension_Name,
		Implementation_Title,
		Implementation_Vendor_Id,
		Automatic_Module_Name,
		Bundle_SymbolicName,
		Main_Class;
		
		public static final Set<String> stringValues = Arrays.stream(Attribute.values())
				.map(Attribute::toString)
				.collect(Collectors.toSet());
				
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
		public List<ScoredValue> getCandidates(String attributeValue);
	}
	
	public static class ScoredValue {
		public final String value;
		public final int confidence;
		public ScoredValue(String value, int confidence) {
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
			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 4, 2),
			Attribute.Implementation_Title,
			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 4, 2),
			Attribute.Implementation_Vendor_Id,
			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 8, 4),
			Attribute.Automatic_Module_Name,
			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 2, 4),
			Attribute.Bundle_SymbolicName,
			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 2, 4),
			Attribute.Main_Class,
			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 2, 2)
	);
	
	public static Map<Attribute, ValueCandidatesExtractor> artifactIdExtractors = Map.of(
			Attribute.Extension_Name,
			attributeValue -> extractPattern_ArtifactIdOrPackageLeaf(attributeValue, 8, 2),
			Attribute.Implementation_Title,
			attributeValue -> extractPattern_ArtifactIdOrPackageLeaf(attributeValue, 4, 2),
			Attribute.Implementation_Vendor_Id,
			attributeValue -> extractPattern_PackageLeaf(attributeValue, 1),
			Attribute.Automatic_Module_Name,
			attributeValue -> extractPattern_PackageLeaf(attributeValue, 4),
			Attribute.Bundle_SymbolicName,
			attributeValue -> extractPattern_PackageLeafOrArtifactLeaf(attributeValue, 2)
	);
	
	/**
	 * Extract package name and package name subpatterns (package: foo.bar.baz, subpattern: foo.bar)
	 */
	private static List<ScoredValue> extractPattern_PackageWithOptionalClass(String attributeValue, int confidenceExactMatch, int confidenceSubpatternMatch) {
		
		Matcher matcher = Regex.packageWithOptionalClass.matcher(attributeValue);
		if (!matcher.find()) {
			return List.of();
		}
		String pakkage = matcher.group(Regex.CAP_GROUP_PACKAGE);
		
		if (pakkage == null) {
			return List.of();
		} else {
			var candidates = CandidateExtractionHelper.getPackageCandidates(pakkage);
			var result = new ArrayList<ScoredValue>(candidates.size());
			for (String candidate : candidates) {
				int confidence = candidate.equals(attributeValue) ? confidenceExactMatch : confidenceSubpatternMatch;
				result.add(new ScoredValue(candidate, confidence));
			}
			return result;
		}
	}
	
	/**
	 * If value matches package pattern (foo.bar.baz), extract leaf (baz).
	 * If value matches artifactId, take that. 
	 */
	private static List<ScoredValue> extractPattern_ArtifactIdOrPackageLeaf(String attributeValue, int confidenceArtifact, int confidenceLeaf) {
		Matcher matcher = Regex.artifactId.matcher(attributeValue);
		if (matcher.find()) {
			String artifactId = matcher.group(Regex.CAP_GROUP_ARTIFACT_ID);
			if (artifactId != null) {
				return List.of(new ScoredValue(artifactId, confidenceArtifact));
			}
		}
		return extractPattern_PackageLeaf(attributeValue, confidenceLeaf);
	}
	
	/**
	 * If value matches package pattern (foo.bar.baz), extract leaf (baz).
	 */
	private static List<ScoredValue> extractPattern_PackageLeaf(String attributeValue, int confidenceLeaf) {
		Matcher matcher = Regex.packageWithOptionalClass.matcher(attributeValue);
		if (matcher.find()) {
			String pakkage = matcher.group(Regex.CAP_GROUP_PACKAGE);
			if (pakkage != null) {
				String leaf = CandidateExtractionHelper.getPackageLeaf(pakkage);
				return List.of(new ScoredValue(leaf, confidenceLeaf));
			}
		}
		return List.of();
	}
	
	/**
	 * If value matches package pattern with leaf somewhat (foo.bar-baz), extract leaf (bar-baz).
	 * Unlike {@link #extractPattern_PackageLeaf(String, int)}, this pattern allows leafs to contain hyphen.
	 */
	private static List<ScoredValue> extractPattern_PackageLeafOrArtifactLeaf(String attributeValue, int confidenceLeaf) {
		Matcher matcher = Regex.optionalPackageWithArtifactIdAsLeaf.matcher(attributeValue);
		if (matcher.find()) {
			String pakkage = matcher.group(Regex.CAP_GROUP_PACKAGE);
			if (pakkage != null) {
				String leaf = CandidateExtractionHelper.getPackageLeaf(pakkage);
				return List.of(new ScoredValue(leaf, confidenceLeaf));
			}
		}
		return List.of();
	}
	
	private static class Regex {
		
		// capture group names
		public final static String CAP_GROUP_PACKAGE = "package";
		public final static String CAP_GROUP_CLASS = "class";
		public final static String CAP_GROUP_ARTIFACT_ID = "artifactId";
		
		// reusable patterns
		public final static String PATTERN_CLASS = "[A-Z]\\w*";
		public final static String PATTERN_SUBPACKAGE = "[a-z_][a-z0-9_]*";
		public final static String PATTERN_ARTIFACT_ID = "[a-z_][a-z0-9_\\-]*";
		public final static String PATTERN_PACKAGE_STRICT = "(" + PATTERN_SUBPACKAGE + "\\.)+(" + PATTERN_SUBPACKAGE + ")";

		// specific patterns to test values with, using capture groups to extract substrings
		public final static String PACKAGE_WITH_OPTIONAL_CLASS =
				"^(?<" + CAP_GROUP_PACKAGE + ">" + PATTERN_PACKAGE_STRICT + ")(\\.(?<" + CAP_GROUP_CLASS + ">" + PATTERN_CLASS + "))?$";
		
		public final static String ARTIFACT_ID =
				"^(?<" + CAP_GROUP_ARTIFACT_ID + ">" + PATTERN_ARTIFACT_ID + ")$";
		
		public final static String OPTIONAL_PACKAGE_WITH_ARTIFACT_ID_AS_LEAF =
				"^(?<" + CAP_GROUP_PACKAGE + ">" + "(" + PATTERN_SUBPACKAGE + "\\.)*(" + PATTERN_ARTIFACT_ID + ")" + ")$";
		
		// precompiled
		public final static Pattern packageWithOptionalClass = Pattern.compile(PACKAGE_WITH_OPTIONAL_CLASS);
		public final static Pattern artifactId = Pattern.compile(ARTIFACT_ID);
		public final static Pattern optionalPackageWithArtifactIdAsLeaf = Pattern.compile(OPTIONAL_PACKAGE_WITH_ARTIFACT_ID_AS_LEAF);
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
		
		public static String getPackageLeaf(String pakkage) {
			String[] parts = pakkage.split("\\.");
			return parts[parts.length - 1];
		}
	}
}
