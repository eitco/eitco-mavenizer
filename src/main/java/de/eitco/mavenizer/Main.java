package de.eitco.mavenizer;

import java.io.IOException;
import java.nio.file.Paths;


public class Main {

	public static void main(String[] args) throws IOException {
		var jarsDir = Paths.get("./jars");
		new AnalyzerService().runAnalysis(jarsDir);
	}
}
