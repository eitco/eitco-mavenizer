package de.eitco.mavenizer.analyse;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import de.eitco.mavenizer.StringUtil;
import de.eitco.mavenizer.AnalyzerService.Analyzer;
import de.eitco.mavenizer.AnalyzerService.MavenUidComponent;
import de.eitco.mavenizer.AnalyzerService.ScoredValue;
import de.eitco.mavenizer.AnalyzerService.StringValueSource;
import de.eitco.mavenizer.AnalyzerService.ValueCandidate;

public class ClassFilepathAnalyzer {
	
	private final static Path CURRENT_DIR = Paths.get(".");
	
	public static final class FolderStats {
		public final Path path;
		public final int deepClassCount;
		
		public FolderStats(Path path, int deepClassCount) {
			this.path = path;
			this.deepClassCount = deepClassCount;
		}
		@Override
		public String toString() {
			return "[" + deepClassCount + ", " + path.toString() + "]";
		}
	}
	
	public class FolderNode {
		public final Path path;
		public final String folderName;
		public int classCount;
		public Map<String, FolderNode> children;

		public FolderNode(Path path) {
			this.path = path;
			this.folderName = path.getFileName().toString();
		}

		/**
		 * Recursively walk tree to add classCount in correct FolderNode. Creates any missing FolderNodes.
		 * @param fullPath - Path of folder containing the class file, relative to ROOT NODE.
		 * @param relativePath - Path of folder containing the class file, relative to THIS NODE.
		 */
		public void incrementClassCount(Path fullPath, Path relativePath) {
			if (relativePath.equals(CURRENT_DIR)) {
				// class found inside this folder
				classCount++;
			} else {
				// class belongs in some subfolder
				if (children == null) {
					children = new HashMap<>();
				}
				var childName = relativePath.getName(0).toString();
				var childFolder = children.computeIfAbsent(childName, key -> new FolderNode(path.resolve(childName)));
				var pathDepth = relativePath.getNameCount();
				var subPath = pathDepth == 1 ? CURRENT_DIR : relativePath.subpath(1, pathDepth);
				childFolder.incrementClassCount(fullPath, subPath);
			}
		}
		
		public int getStats(List<FolderStats> stats) {
			int absoluteCount;
			if (children == null) {
				absoluteCount = classCount;
			} else {
				absoluteCount = children.values().stream()
					.mapToInt(node -> node.getStats(stats))
					.sum();
			}
			stats.add(new FolderStats(path, absoluteCount));
			return absoluteCount;
		}
		
		public void print(int padding) {
			System.out.println(" ".repeat(padding) + folderName + ": " + classCount);
			if (children != null) {
				for (FolderNode child : children.values()) {
					child.print(padding + 2);
				}
			}
		}
	}
	
	public Map<MavenUidComponent, List<ValueCandidate>> analyze(List<Path> paths) {
		
		var result = Map.<MavenUidComponent, List<ValueCandidate>>of(
				MavenUidComponent.GROUP_ID, new ArrayList<ValueCandidate>(),
				MavenUidComponent.ARTIFACT_ID, List.of(),
				MavenUidComponent.VERSION, List.of()
				);
		
		// convert paths into tree structure to make it possible to walk the tree to gather folder statistics
		FolderNode folderTree = new FolderNode(CURRENT_DIR);
		for (var path : paths) {
			var parent = path.getParent();
			var versioned = path.startsWith(Paths.get("META-INF/versions"));
			
			if (parent != null && !versioned) {
				folderTree.incrementClassCount(parent, parent);
			}
		}
		
		// gather relevant folder statistics (recursive/deep class count for each folder)
		var statsList = new ArrayList<FolderStats>();
		var totalClassCount = folderTree.getStats(statsList);
		statsList.sort(Comparator.comparing((FolderStats stats) -> stats.deepClassCount).reversed());
		
		var resultGroupIds = result.get(MavenUidComponent.GROUP_ID);
		
		// filter for folders with high number of classes to get groupId candidates
		for (var stats : statsList) {
			var pathDepth = stats.path.getNameCount();
			if (pathDepth >= 3 && pathDepth <= 5) {
				var countRatio = (float)stats.deepClassCount / totalClassCount;
				
				if (countRatio > 0.6) {
					int confidence = (int)((countRatio * 2) + 0.5);
					var pathWithoutTreeRoot = stats.path.subpath(1, stats.path.getNameCount());
					var pakkage = pathWithoutTreeRoot.toString().replace('\\', '.').replace('/', '.');
					
					Matcher matcher = Helper.Regex.packageWithOptionalClass.matcher(pakkage);
					if (matcher.find()) {
						String validPackage = matcher.group(Helper.Regex.CAP_GROUP_PACKAGE);
						var value = new ScoredValue(validPackage, confidence);
						
						var countRatioPercent = StringUtil.leftPad((int)(countRatio * 100) + "", 3);
						var sourceString = "Path contains " + countRatioPercent + "% of classes: '" + pathWithoutTreeRoot.toString() + "'";
						resultGroupIds.add(new ValueCandidate(value, Analyzer.CLASS_FILEPATH, new StringValueSource(sourceString)));
					}
				}
			}
		}

		return result;
	}
}
