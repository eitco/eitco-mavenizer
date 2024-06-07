package de.eitco.mavenizer;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.eitco.mavenizer.analyze.Analyzer;
import de.eitco.mavenizer.generate.Generator;

import java.util.Arrays;
import java.util.Optional;

public class Main {

	public static class DefaultArgs {
		@Parameter(names = { "-help", "--help", "-h" }, help = true)
		public boolean help = false;
	}

	private static final Logger LOG = LoggerFactory.getLogger(Main.class);
	
	public static void main(String[] args) {
		new Main(args);
	}

    private final DefaultArgs defaultArgs;
	private final JCommander commander;
	
	public Main(String[] args) {
		
		Cli cli = new Cli();
		defaultArgs = new DefaultArgs();
		commander = buildCli();

        Analyzer analyzer = new Analyzer(cli);
        analyzer.addCommand(commander::addCommand);

        Generator generator = new Generator(cli);
        generator.addCommand(commander::addCommand);

		LOG.info("Args: " + Arrays.toString(args));
		var command = tryParseArgs(cli, args);
		if (command.isPresent()) {
			if (Analyzer.COMMAND_NAME.equals(command.get())) {
				analyzer.runAnalysis();
			}
			if (Generator.COMMAND_NAME.equals(command.get())) {
				generator.runGenerator();
			}
		}
		
		cli.println("Exiting.", LOG::info);
	}

	private JCommander buildCli() {
		return JCommander.newBuilder()
				.addObject(defaultArgs)
				.columnSize(140)
				.build();
	}

	public Optional<String> tryParseArgs(Cli cli, String[] args) {
		if (args.length == 0) {
			cli.println("No arguments provided!");
			commander.usage();
			return Optional.empty();
		}

		try {
			commander.parse(args);
		} catch(ParameterException e) {
			cli.println("Argument error: " + e.getMessage());
			cli.println("Enter '-help' to show descriptions.");
			return Optional.empty();
		}

		if (defaultArgs.help) {
			commander.usage();
			return Optional.empty();
		} else {
			return Optional.of(commander.getParsedCommand());
		}
	}
}
