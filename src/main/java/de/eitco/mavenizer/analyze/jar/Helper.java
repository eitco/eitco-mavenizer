package de.eitco.mavenizer.analyze.jar;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import de.eitco.mavenizer.MavenUid.MavenUidComponent;

public class Helper {

	public static class Regex {
		
		// capture group names
		public final static String CAP_GROUP_PACKAGE = "package";
		public final static String CAP_GROUP_CLASS = "class";
		public final static String CAP_GROUP_ARTIFACT_ID = "artifactId";
		public final static String CAP_GROUP_VERSION = "version";
		public final static String CAP_GROUP_GROUP_ID = "groupId";
		
		// reusable patterns
		public final static String PATTERN_CLASS = "[A-Z]\\w*";
		public final static String PATTERN_SUBPACKAGE = "[a-z_][a-z0-9_]*";
		public final static String PATTERN_ARTIFACT_ID_STRICT = "[a-z_][a-z0-9_\\-]*";
		public final static String PATTERN_ARTIFACT_ID_LIKE = "[a-zA-Z_][a-zA-Z0-9_\\-\\.]*";// sadly, log4j-1.2-api is a real artifactId, so we want to include period, and allow uppercase letters
		public final static String PATTERN_PACKAGE = "(" + PATTERN_SUBPACKAGE + "\\.)*(" + PATTERN_SUBPACKAGE + ")";
		public final static String PATTERN_GROUP_ID_LIKE = "(" + PATTERN_PACKAGE + ")|([a-z_][a-z0-9_\\-]*)";// can be a package, but sadly could be something like "xml-apis"
		public final static String PATTERN_PACKAGE_2_OR_MORE = "(" + PATTERN_SUBPACKAGE + "\\.)+(" + PATTERN_SUBPACKAGE + ")";
		public final static String PATTERN_CLASSIFIER = "(([0-9]+)|([a-zA-Z]+))";
		
		public final static String PATTERN_VERSION = "[0-9]+(\\.[0-9]+)*((\\.[A-Z]+)|(\\-[A-Z]+))?";// yes, "3.1.SONATYPE" is a version used in reality
		public final static String PATTERN_CLASSIFIERS = "(" + PATTERN_CLASSIFIER + ")(\\-" + PATTERN_CLASSIFIER + ")?";// more than 2 classifiers is unrealistic
		
		// specific patterns to test values with, using capture groups to extract substrings
		public final static String PACKAGE_WITH_OPTIONAL_CLASS =
				"^(?<" + CAP_GROUP_PACKAGE + ">" + PATTERN_PACKAGE + ")(\\.(?<" + CAP_GROUP_CLASS + ">" + PATTERN_CLASS + "))?$";
		
		public final static String PACKAGE_2_OR_MORE_WITH_OPTIONAL_CLASS =
				"^(?<" + CAP_GROUP_PACKAGE + ">" + PATTERN_PACKAGE_2_OR_MORE + ")(\\.(?<" + CAP_GROUP_CLASS + ">" + PATTERN_CLASS + "))?$";
		
		public final static String ARTIFACT_ID_LIKE =
				"^(?<" + CAP_GROUP_ARTIFACT_ID + ">" + PATTERN_ARTIFACT_ID_LIKE + ")$";
		
		public final static String ARTIFACT_ID_STRICT =
				"^(?<" + CAP_GROUP_ARTIFACT_ID + ">" + PATTERN_ARTIFACT_ID_STRICT + ")$";
		
		public final static String OPTIONAL_PACKAGE_WITH_ARTIFACT_ID_LIKE_AS_LEAF =
				"^(?<" + CAP_GROUP_PACKAGE + ">" + "(" + PATTERN_SUBPACKAGE + "\\.)*)(?<" + CAP_GROUP_ARTIFACT_ID + ">" + PATTERN_ARTIFACT_ID_LIKE + ")$";
		
		public final static String JAR_FILENAME_VERSION_SUFFIX =
				"\\-(?<" + CAP_GROUP_VERSION + ">" + PATTERN_VERSION + ")([\\-\\.]" + PATTERN_CLASSIFIERS + ")?$";
		
		public final static String ATTRIBUTE_VERSION =
				"^(?<" + CAP_GROUP_VERSION + ">" + PATTERN_VERSION + ")([\\-\\.]" + PATTERN_CLASSIFIERS + ")?$";
		
		public final static String GROUP_ID = 
				"^(?<" + CAP_GROUP_GROUP_ID + ">" + PATTERN_GROUP_ID_LIKE + ")$";
		
		public final static String VERSION =
				"^(?<" + CAP_GROUP_VERSION + ">" + PATTERN_VERSION + ")$";
		
		// precompiled
		public final static Pattern packageWithOptionalClass = Pattern.compile(PACKAGE_WITH_OPTIONAL_CLASS);
		public final static Pattern packageStrictWithOptionalClass = Pattern.compile(PACKAGE_2_OR_MORE_WITH_OPTIONAL_CLASS);
		public final static Pattern artifactId = Pattern.compile(ARTIFACT_ID_STRICT);
		public final static Pattern artifactIdLike = Pattern.compile(ARTIFACT_ID_LIKE);
		public final static Pattern optionalPackageWithArtifactIdLikeAsLeaf = Pattern.compile(OPTIONAL_PACKAGE_WITH_ARTIFACT_ID_LIKE_AS_LEAF);
		public final static Pattern jarFilenameVersionSuffix = Pattern.compile(JAR_FILENAME_VERSION_SUFFIX);
		public final static Pattern attributeVersion = Pattern.compile(ATTRIBUTE_VERSION);
		public final static Pattern groupId = Pattern.compile(GROUP_ID);
		public final static Pattern version = Pattern.compile(VERSION);
		
		public static Pattern getPatternForUserInputValidation(MavenUidComponent component) {
			switch(component) {
			case GROUP_ID: return Regex.groupId;
			case ARTIFACT_ID: return Regex.artifactIdLike;
			case VERSION: return Regex.version;
			}
			throw new IllegalStateException();
		}
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
