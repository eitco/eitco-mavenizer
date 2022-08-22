package de.eitco.mavenizer.analyzer;

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

import de.eitco.mavenizer.AnalyzerService.Analyzer;
import de.eitco.mavenizer.AnalyzerService.ValueCandidateCollector;
import de.eitco.mavenizer.MavenUid.MavenUidComponent;

public class ManifestAnalyzer {
	
	public void analyze(ValueCandidateCollector result, Optional<Manifest> manifestOptional) {
		
		if (!manifestOptional.isPresent()) {
			return;
		}
		var manifest = manifestOptional.get();
		
		for (var entry : manifest.getMainAttributes().entrySet()) {
			String attrName = ((Attributes.Name) entry.getKey()).toString();
			
			if (attrName.endsWith(VERSION_ATTRIBUTE_SUFFIX) && !VERSION_ATTRIBUTE_EXCLUDES.contains(attrName)) {
				var uidComponent = MavenUidComponent.VERSION;
				var attrValue = ((String) entry.getValue()).trim();
				
				Matcher matcher = Helper.Regex.attributeVersion.matcher(attrValue);
				if (matcher.find()) {
					String version = matcher.group(Helper.Regex.CAP_GROUP_VERSION);
					if (version != null) {
						var attrSource = attrName + ": '" + attrValue + "'";
						
						result.addCandidate(uidComponent, version, 2, attrSource);
						if (!version.equals(attrValue)) {
							result.addCandidate(uidComponent, attrValue, 1, attrSource);
						}
					}
				}
			} else if (Attribute.stringValues.contains(attrName)) {
				
				var attr = Attribute.fromString(attrName);
				var attrValue = ((String) entry.getValue()).trim();
				var attrSource = attr.toString() + ": '" + attrValue + "'";
				
				for (var uidComponent : List.of(MavenUidComponent.GROUP_ID, MavenUidComponent.ARTIFACT_ID)) {
//					var resultList = result.get(uidComponent);
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
	
	public Analyzer getType() {
		return Analyzer.MANIFEST;
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
	
	public static class ScoredValue {
		public final String value;
		public final int confidence;
		
		public ScoredValue(String value, int confidence) {
			this.value = value;
			this.confidence = confidence;
		}
	}
	
	@FunctionalInterface
	public static interface CandidatesExtractor {
		public List<ScoredValue> getCandidates(String attributeValue);
	}

	public static Map<Attribute, CandidatesExtractor> groupIdExtractors = Map.of(
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
	
	public static Map<Attribute, CandidatesExtractor> artifactIdExtractors = Map.of(
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
	
	public static String VERSION_ATTRIBUTE_SUFFIX = "-Version";
	public static Set<String> VERSION_ATTRIBUTE_EXCLUDES = Set.of(
			"Ant-Version",
			"Manifest-Version",
			"Bundle-ManifestVersion",
			"Archiver-Version",
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
	 * Unlike {@link #extractPattern_PackageLeaf(String, int)}, this pattern allows leafs to contain hyphen.
	 */
	private static List<ScoredValue> extractPattern_PackageLeafOrArtifactLeaf(String attributeValue, int confidenceLeaf) {
		Matcher matcher = Helper.Regex.optionalPackageWithArtifactIdAsLeaf.matcher(attributeValue);
		if (matcher.find()) {
			String pakkage = matcher.group(Helper.Regex.CAP_GROUP_PACKAGE);
			if (pakkage != null) {
				String leaf = Helper.CandidateExtractionHelper.getPackageLeaf(pakkage);
				return List.of(new ScoredValue(leaf, confidenceLeaf));
			}
		}
		return List.of();
	}
}
