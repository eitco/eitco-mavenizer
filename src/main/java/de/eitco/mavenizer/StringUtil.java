package de.eitco.mavenizer;

public class StringUtil {

	public static String rightPad(String str, int minLength) {
		if (str.length() < minLength) {
			int padLength = minLength - str.length();
			return str + " ".repeat(padLength);
		} else {
			return str;
		}
	}

	public static String leftPad(String str, int minLength) {
		if (str.length() < minLength) {
			int padLength = minLength - str.length();
			return " ".repeat(padLength) + str;
		} else {
			return str;
		}
	}

}
