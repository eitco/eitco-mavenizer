package de.eitco.mavenizer;

import java.io.IOException;

import de.eitco.mavenizer.analyze.Analyzer;

public class Main {

	public static void main(String[] args) throws IOException {
		new Main(args);
	}
	
	Analyzer analyzer = new Analyzer();
	
	public Main(String[] args) {
		
		Cli cli = new Cli();
		analyzer.addCommand(cli);
		cli.parseArgsOrRetry(args);
		
		analyzer.runAnalysis(cli);
	}
}
