package de.eitco.mavenizer.analyze.jar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import de.eitco.mavenizer.MavenUid.MavenUidComponent;
import de.eitco.mavenizer.analyze.JarAnalyzer.JarAnalyzerType;
import de.eitco.mavenizer.analyze.JarAnalyzer.ValueCandidateCollector;

public class ManifestAnalyzer {
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void analyze(ValueCandidateCollector result, Optional<Manifest> manifestOptional) {
		
		if (!manifestOptional.isPresent()) {
			return;
		}
		var manifest = manifestOptional.get();
		
		analyze(result, (Set) manifest.getMainAttributes().entrySet());
		for (var subPathAttributes : manifest.getEntries().values()) {
			// Manifest can specify attributes that only apply to classes in certain sub-packages.
			analyze(result, (Set) subPathAttributes.entrySet());
		}
	}
	
	public JarAnalyzerType getType() {
		return JarAnalyzerType.MANIFEST;
	}
	
	private void analyze(ValueCandidateCollector result, Set<Map.Entry<Attributes.Name, String>> attributes) {
		for (var entry : attributes) {
			String attrName = entry.getKey().toString();
			
			if (attrName.endsWith(VERSION_ATTRIBUTE_SUFFIX) && !VERSION_ATTRIBUTE_EXCLUDES.contains(attrName)) {
				var uidComponent = MavenUidComponent.VERSION;
				var attrValue = entry.getValue().trim();
				
				Matcher matcher = Helper.Regex.versionWidthOptionalClassifiers.matcher(attrValue);
				if (matcher.find()) {
					String version = matcher.group(Helper.Regex.CAP_GROUP_VERSION);
					if (version != null) {
						boolean hasClassifiers = !version.equals(attrValue);
						var attrSource = attrName + ": '" + attrValue + "'";
						// version without classifiers
						{
							var confidence = !hasClassifiers ? 3 : 1;
							if (VERSION_ATTRIBUTE_LOW_CONFIDENCE.contains(attrName)) {
								confidence--;
							}
							result.addCandidate(uidComponent, version, confidence, attrSource);
						}
						// version with classifiers if it exists
						if (hasClassifiers) {
							var confidence = 1;
							if (VERSION_ATTRIBUTE_LOW_CONFIDENCE.contains(attrName)) {
								confidence--;
							}
							result.addCandidate(uidComponent, attrValue, confidence, attrSource);
						}
					}
				}
			} else if (Attribute.stringValues.contains(attrName)) {
				
				var attr = Attribute.fromString(attrName);
				var attrValue = entry.getValue().trim();
				var attrSource = attr.toString() + ": '" + attrValue + "'";
				
				for (var uidComponent : List.of(MavenUidComponent.GROUP_ID, MavenUidComponent.ARTIFACT_ID)) {
					Map<Attribute, CandidatesExtractor> extractors = null;
					
					switch (uidComponent) {
					case GROUP_ID:
						extractors = groupIdExtractors;
						break;
					case ARTIFACT_ID:
						extractors = artifactIdExtractors;
						break;
					default:
						throw new IllegalStateException();
					}
					
					var extractor = extractors.get(attr);
					if (extractor != null) {
						var candidates = extractor.getCandidates(attrValue);
						for (ScoredValue scoredValue : candidates) {
							result.addCandidate(uidComponent, scoredValue.value, scoredValue.confidence, attrSource);
						}
					}
				}
			}
		}
	}

	private enum Attribute {
		Extension_Name,
		Implementation_Title,
		Implementation_Vendor_Id,
		Automatic_Module_Name,
		Bundle_SymbolicName,
		Main_Class;
		
		public static final Set<String> stringValues = Arrays.stream(Attribute.values())
				.map(Attribute::toString)
				.collect(Collectors.toUnmodifiableSet());
				
		@Override
		public String toString() {
			return this.name().replace('_', '-');
		}
		public static Attribute fromString(String attributeName) {
			return Attribute.valueOf(attributeName.replace('-', '_'));
		}
	}
	
	private static class ScoredValue {
		public final String value;
		public final int confidence;
		
		public ScoredValue(String value, int confidence) {
			this.value = value;
			this.confidence = confidence;
		}
	}
	
	@FunctionalInterface
	private static interface CandidatesExtractor {
		public List<ScoredValue> getCandidates(String attributeValue);
	}

	private static Map<Attribute, CandidatesExtractor> groupIdExtractors = Map.of(
			Attribute.Extension_Name,
			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 4, 2),
			Attribute.Implementation_Title,
			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 4, 2),
			Attribute.Implementation_Vendor_Id,
			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 6, 4),
			Attribute.Automatic_Module_Name,
			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 2, 4),
			Attribute.Bundle_SymbolicName,
			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 2, 4),
			Attribute.Main_Class,
			attributeValue -> extractPattern_PackageWithOptionalClass(attributeValue, 2, 2)
	);
	
	private static Map<Attribute, CandidatesExtractor> artifactIdExtractors = Map.of(
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
	
	private static String VERSION_ATTRIBUTE_SUFFIX = "Version";
	private static Set<String> VERSION_ATTRIBUTE_EXCLUDES = Set.of(
			"Ant-Version",
			"Manifest-Version",
			"Bundle-ManifestVersion",
			"Archiver-Version"
			);
	private static Set<String> VERSION_ATTRIBUTE_LOW_CONFIDENCE = Set.of(
			"Specification-Version" // this attribute sometimes does not contain the minor version and is usually not the only version attribute anyway
			);
	
	/**
	 * Extract package name and package name subpatterns (package: foo.bar.baz, subpattern: foo.bar)
	 */
	private static List<ScoredValue> extractPattern_PackageWithOptionalClass(String attributeValue, int confidenceExactMatch, int confidenceSubpatternMatch) {
		
		Matcher matcher = Helper.Regex.packageStrictWithOptionalClass.matcher(attributeValue);
		if (!matcher.find()) {
			return List.of();
		}
		String pakkage = matcher.group(Helper.Regex.CAP_GROUP_PACKAGE);
		
		if (pakkage == null) {
			return List.of();
		} else {
			var candidates = Helper.CandidateExtractionHelper.getPackageCandidates(pakkage);
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
		Matcher matcher = Helper.Regex.artifactId.matcher(attributeValue);
		if (matcher.find()) {
			String artifactId = matcher.group(Helper.Regex.CAP_GROUP_ARTIFACT_ID);
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
		Matcher matcher = Helper.Regex.packageStrictWithOptionalClass.matcher(attributeValue);
		if (matcher.find()) {
			String pakkage = matcher.group(Helper.Regex.CAP_GROUP_PACKAGE);
			if (pakkage != null) {
				String leaf = Helper.CandidateExtractionHelper.getPackageLeaf(pakkage);
				return List.of(new ScoredValue(leaf, confidenceLeaf));
			}
		}
		return List.of();
	}
	
	/**
	 * If value matches package pattern with leaf somewhat (foo.bar-baz), extract leaf (bar-baz).
	 * Unlike {@link #extractPattern_PackageLeaf(String, int)}, this pattern allows leafs to contain hyphens and periods.
	 */
	private static List<ScoredValue> extractPattern_PackageLeafOrArtifactLeaf(String attributeValue, int confidenceLeaf) {
		Matcher matcher = Helper.Regex.optionalPackageWithArtifactIdLikeAsLeaf.matcher(attributeValue);
		if (matcher.find()) {
			String leaf = matcher.group(Helper.Regex.CAP_GROUP_ARTIFACT_ID);
			if (leaf != null) {
				return List.of(new ScoredValue(leaf, confidenceLeaf));
			}
		}
		return List.of();
	}
}
