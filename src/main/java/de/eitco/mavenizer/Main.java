package de.eitco.mavenizer;

import java.io.IOException;

public class Main {

	public static void main(String[] args) throws IOException {
		new Main(args);
	}
	
	AnalyzerService analyzer = new AnalyzerService();
	
	public Main(String[] args) {
		
		Cli cli = new Cli();
		analyzer.addCommand(cli);
		cli.parseArgsOrRetry(args);
		
		analyzer.runAnalysis(cli);
	}
}
