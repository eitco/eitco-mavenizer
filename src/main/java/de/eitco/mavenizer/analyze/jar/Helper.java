package de.eitco.mavenizer.analyze.jar;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import de.eitco.mavenizer.MavenUid.MavenUidComponent;

public class Helper {
	private Helper() {}
	
	public static class Regex {
		private Regex() {}
		
		// capture group names
		public static final String CAP_GROUP_PACKAGE = "package";
		public static final String CAP_GROUP_CLASS = "class";
		public static final String CAP_GROUP_ARTIFACT_ID = "artifactId";
		public static final String CAP_GROUP_VERSION = "version";
		public static final String CAP_GROUP_GROUP_ID = "groupId";
		
		// reusable patterns
		public static final String PATTERN_CLASS = "[A-Z]\\w*";
		public static final String PATTERN_SUBPACKAGE = "[a-z_][a-z0-9_]*";
		public static final String PATTERN_ARTIFACT_ID_STRICT = "[a-z_][a-z0-9_\\-]*";
		public static final String PATTERN_ARTIFACT_ID_LIKE = "[a-zA-Z_][a-zA-Z0-9_\\-\\.]*";// sadly log4j-1.2-api is a real artifactId so we want to include period, allow uppercase letters
		public static final String PATTERN_PACKAGE = "(" + PATTERN_SUBPACKAGE + "\\.)*(" + PATTERN_SUBPACKAGE + ")";
		public static final String PATTERN_GROUP_ID_LIKE = "(" + PATTERN_PACKAGE + ")|([a-z_][a-z0-9_\\-]*)";// can be a package, but sadly could be something like "xml-apis"
		public static final String PATTERN_PACKAGE_2_OR_MORE = "(" + PATTERN_SUBPACKAGE + "\\.)+(" + PATTERN_SUBPACKAGE + ")";
		public static final String PATTERN_CLASSIFIER = "([a-zA-Z0-9]+)";
		
		public static final String PATTERN_VERSION = "[0-9]+(\\.[0-9]+)*((\\.[A-Z]+)|(\\-[A-Z]+))?";// yes, "3.1.SONATYPE" is a version used in reality
		public static final String PATTERN_CLASSIFIERS = "(" + PATTERN_CLASSIFIER + ")([\\-\\.]" + PATTERN_CLASSIFIER + "){0,2}";// "2.4.0-b180830.0438" and "2.1.4-hudson-build-463" are also real versions
		
		// specific patterns to test values with, using capture groups to extract substrings
		public static final String PACKAGE_WITH_OPTIONAL_CLASS =
				"^(?<" + CAP_GROUP_PACKAGE + ">" + PATTERN_PACKAGE + ")(\\.(?<" + CAP_GROUP_CLASS + ">" + PATTERN_CLASS + "))?$";
		
		public static final String PACKAGE_2_OR_MORE_WITH_OPTIONAL_CLASS =
				"^(?<" + CAP_GROUP_PACKAGE + ">" + PATTERN_PACKAGE_2_OR_MORE + ")(\\.(?<" + CAP_GROUP_CLASS + ">" + PATTERN_CLASS + "))?$";
		
		public static final String ARTIFACT_ID_LIKE =
				"^(?<" + CAP_GROUP_ARTIFACT_ID + ">" + PATTERN_ARTIFACT_ID_LIKE + ")$";
		
		public static final String ARTIFACT_ID_STRICT =
				"^(?<" + CAP_GROUP_ARTIFACT_ID + ">" + PATTERN_ARTIFACT_ID_STRICT + ")$";
		
		public static final String OPTIONAL_PACKAGE_WITH_ARTIFACT_ID_LIKE_AS_LEAF =
				"^(?<" + CAP_GROUP_PACKAGE + ">" + "(" + PATTERN_SUBPACKAGE + "\\.)*)(?<" + CAP_GROUP_ARTIFACT_ID + ">" + PATTERN_ARTIFACT_ID_LIKE + ")$";
		
		public static final String JAR_FILENAME_VERSION_SUFFIX =
				"\\-(?<" + CAP_GROUP_VERSION + ">" + PATTERN_VERSION + ")([\\-\\.]" + PATTERN_CLASSIFIERS + ")?$";
		
		public static final String VERSION_WITH_OPTIONAL_CLASSIFIERS =
				"^(?<" + CAP_GROUP_VERSION + ">" + PATTERN_VERSION + ")([\\-\\.]" + PATTERN_CLASSIFIERS + ")?$";
		
		public static final String GROUP_ID = 
				"^(?<" + CAP_GROUP_GROUP_ID + ">" + PATTERN_GROUP_ID_LIKE + ")$";
		
		// precompiled
		public static final Pattern packageWithOptionalClass = Pattern.compile(PACKAGE_WITH_OPTIONAL_CLASS);
		public static final Pattern packageStrictWithOptionalClass = Pattern.compile(PACKAGE_2_OR_MORE_WITH_OPTIONAL_CLASS);
		public static final Pattern artifactId = Pattern.compile(ARTIFACT_ID_STRICT);
		public static final Pattern artifactIdLike = Pattern.compile(ARTIFACT_ID_LIKE);
		public static final Pattern optionalPackageWithArtifactIdLikeAsLeaf = Pattern.compile(OPTIONAL_PACKAGE_WITH_ARTIFACT_ID_LIKE_AS_LEAF);
		public static final Pattern jarFilenameVersionSuffix = Pattern.compile(JAR_FILENAME_VERSION_SUFFIX);
		public static final Pattern versionWidthOptionalClassifiers = Pattern.compile(VERSION_WITH_OPTIONAL_CLASSIFIERS);
		public static final Pattern groupId = Pattern.compile(GROUP_ID);
		
		public static Pattern getPatternForUserInputValidation(MavenUidComponent component) {
			switch(component) {
			case GROUP_ID: return Regex.groupId;
			case ARTIFACT_ID: return Regex.artifactIdLike;
			case VERSION: return Regex.versionWidthOptionalClassifiers;
			}
			throw new IllegalStateException();
		}
	}

	public static class CandidateExtractionHelper {
		private CandidateExtractionHelper() {}
		
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
