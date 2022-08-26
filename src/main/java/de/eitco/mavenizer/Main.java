package de.eitco.mavenizer;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.eitco.mavenizer.analyze.Analyzer;

public class Main {

	private static final Logger LOG = LoggerFactory.getLogger(Main.class);
	
	public static void main(String[] args) throws IOException {
		new Main(args);
	}
	
	Analyzer analyzer = new Analyzer();
	Generator generator = new Generator();
	
	public Main(String[] args) {
		
		Cli cli = new Cli();
		analyzer.addCommand(cli);
		generator.addCommand(cli);
		
		var command = cli.parseArgsOrRetry(args);
		if (Analyzer.COMMAND_NAME.equals(command)) {
			analyzer.runAnalysis(cli);
		}
		if (Generator.COMMAND_NAME.equals(command)) {
			generator.runGenerator(cli);
		}
		
		cli.println("Exiting.", LOG::info);
	}
}
