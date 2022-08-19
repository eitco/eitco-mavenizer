package de.eitco.mavenizer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import edu.rice.cs.util.ArgumentTokenizer;

public class Cli {
	
	public static interface ResettableCommand {
		public void setDefaults();
	}
	
	public static class DefaultArgs implements Cli.ResettableCommand {
		@Parameter(names = { "-help", "--help", "-h" }, help = true)
	    public boolean help;
		
		public DefaultArgs() {
			setDefaults();
		}
		@Override
		public void setDefaults() {
			help = false;
		}
	}
	
	public Scanner scanner;
	
	private JCommander commander;
	private DefaultArgs defaultArgs;
	private Map<String, ResettableCommand> commands;
	
	public Cli() {
		this.defaultArgs = new DefaultArgs();
		this.commands = new HashMap<>();
		this.commander = buildCli();
		this.scanner = new Scanner(System.in);
	}
	
	private JCommander buildCli() {
		var jCommander = JCommander.newBuilder()
			  .addObject(defaultArgs)
			  .columnSize(140)
			  .build();
		for (var entry : commands.entrySet()) {
			jCommander.addCommand(entry.getKey(), entry.getValue());
		}
		return jCommander;
	}
	
	public void addCommand(String name, Cli.ResettableCommand command) {
		commands.put(name, command);
		commander.addCommand(name, command);
	}
	
	public void parseArgs(String[] args) {
		// commands need to be reset before parsing, otherwise previous values are interpreted as default values
		defaultArgs.setDefaults();
		for (var command : commands.values()) {
			command.setDefaults();
		}
		commander.parse(args);
	}
	
	public void printUsage() {
		commander.usage();
	}
	
	public String[] scanArgsBlocking() {
		var argsString = scanner.nextLine();
		return ArgumentTokenizer.tokenize(argsString).toArray(new String[0]);
	}
	
	public void parseArgsOrRetry(String[] args) {
		boolean success = false;
		do {
			try {
				if (args.length == 0) {
					System.out.println("No arguments provided!");
					printUsage();
					args = scanArgsBlocking();
					continue;
				}
				
				// workaround for that fact that JCommander seems to remember previously passed args (??) and therefore
				// fails with "Can only specify option <optiona> once." if invoked twice with the same paramteres.
				commander = buildCli();
				
				parseArgs(args);
				if (defaultArgs.help) {
					printUsage();
					args = scanArgsBlocking();
					continue;
				}
				success = true;
			} catch(ParameterException e) {
				System.out.println("Argument error: " + e.getMessage());
				System.out.println("Enter '-help' to show descriptions.");
				args = scanArgsBlocking();
			}
		} while (!success);
	}
	
	public void validateArgsOrRetry(Supplier<List<Optional<String>>> validator) {
		boolean success = false;
		do {
			var errors = validator.get().stream()
					.flatMap(Optional::stream)
					.collect(Collectors.toList());
			if (errors.isEmpty()) {
				success = true;
			} else {
				if (errors.size() == 1) {
					System.out.println("Argument error: " + errors.get(0));
				} else {
					System.out.println("Argument errors:");
					for (var error : errors) {
						System.out.println("    " + error);
					}
				}
				System.out.println("Enter '-help' to show descriptions.");
				parseArgsOrRetry(scanArgsBlocking());
			}
		} while (!success);
	}

	public void askUserToContinue(String pad) {
		System.out.println(pad + "Press Enter to continue...");
		scanner.nextLine();
	}
}