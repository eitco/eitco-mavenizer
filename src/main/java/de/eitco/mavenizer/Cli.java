package de.eitco.mavenizer;

import java.util.ArrayList;
import java.util.List;
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
	public List<Runnable> setDefaults;
	
	private JCommander commander;
	private Cli.DefaultArgs defaultArgs;
	
	public Cli() {
		this.defaultArgs = new DefaultArgs();
		this.commander = JCommander.newBuilder()
				  .addObject(defaultArgs)
				  .columnSize(140)
				  .build();
		this.scanner = new Scanner(System.in);
		this.setDefaults = new ArrayList<>();
		
		setDefaults.add(defaultArgs::setDefaults);
	}
	
	public void addCommand(String name, Cli.ResettableCommand command) {
		commander.addCommand(name, command);
		setDefaults.add(command::setDefaults);
	}
	
	public void parseArgs(String[] args) {
		// commands need to be reset before parsing, otherwise previous values are interpreted as default values
		for (var setDefault : setDefaults) {
			setDefault.run();
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
}