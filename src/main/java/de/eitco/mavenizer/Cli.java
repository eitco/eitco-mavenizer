package de.eitco.mavenizer;

import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class Cli {

	private final Consumer<String> printConsole;
	private final Supplier<String> readConsole;

	public Cli() {
		this(System.out::println, (new Scanner(System.in))::nextLine);
	}

	public Cli(Consumer<String> printLnConsole, Supplier<String> readLnConsole) {
		this.printConsole = printLnConsole;
		this.readConsole = readLnConsole;
	}
	
	public String readLn() {
		try {
			return readConsole.get();
		} catch (NoSuchElementException e) {
			return null;
		}
	}

	public void askUserToContinue(String pad) {
		printConsole.accept(pad + "Press Enter to continue...");
		readConsole.get();
	}
	
	public void askUserToContinue(String pad, String message) {
		printConsole.accept(pad + message);
		readConsole.get();
	}

	public void println() {
		printConsole.accept("");
	}

	public void println(String msg) {
		printConsole.accept(msg);
	}
	
	public void println(String msg, Consumer<String> logFunction) {
		logFunction.accept(msg);
		printConsole.accept(msg);
	}
}