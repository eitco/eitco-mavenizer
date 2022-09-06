package de.eitco.mavenizer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Scanner;
import java.util.function.Consumer;
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
	
	private Scanner scanner;
	private JCommander commander;
	private String[] lastArgs;
	private DefaultArgs defaultArgs;
	private Map<String, ResettableCommand> commands;
	
	public Cli() {
		this.defaultArgs = new DefaultArgs();
		this.commands = new HashMap<>();
		this.commander = buildCli();
		this.lastArgs = null;
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
	
	private void parseArgs(String[] args) {
		lastArgs = args;
		// commands need to be reset before parsing, otherwise previous values are interpreted as default values
		defaultArgs.setDefaults();
		for (var command : commands.values()) {
			command.setDefaults();
		}
		commander.parse(args);
	}
	
	public String[] getLastArgs() {
		return Arrays.copyOf(lastArgs, lastArgs.length);
	}
	
	public void printUsage() {
		commander.usage();
	}
	
	public String nextLine() {
		try {
			return scanner.nextLine();
		} catch (NoSuchElementException e) {
			return null;
		}
	}
	
	public String[] scanArgsBlocking() {
		var argsString = nextLine();
		return ArgumentTokenizer.tokenize(argsString).toArray(new String[0]);
	}
	
	public String parseArgsOrRetry(String[] args) {
		String command = null;
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
				} else {
					command = commander.getParsedCommand();
				}
			} catch(ParameterException e) {
				System.out.println("Argument error: " + e.getMessage());
				System.out.println("Enter '-help' to show descriptions.");
				args = scanArgsBlocking();
			}
		} while (command == null);
		return command;
	}
	
	public void validateArgsOrRetry(String expectedCommand, Supplier<List<Optional<String>>> validator) {
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
				var command = parseArgsOrRetry(scanArgsBlocking());
				if (!expectedCommand.equals(command)) {
					System.out.println("To execute a different command, please restart this program.");
				}
			}
		} while (!success);
	}

	public void askUserToContinue(String pad) {
		System.out.println(pad + "Press Enter to continue...");
		scanner.nextLine();
	}
	
	public void askUserToContinue(String pad, String message) {
		System.out.println(pad + message);
		scanner.nextLine();
	}
	
	public void println(String msg, Consumer<String> logFunction) {
		logFunction.accept(msg);
		System.out.println(msg);
	}
}