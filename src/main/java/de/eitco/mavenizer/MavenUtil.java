package de.eitco.mavenizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

public class MavenUtil {

	@FunctionalInterface
	public static interface BlockingSupplier<T> {
		T get() throws InterruptedException, ExecutionException, TimeoutException;
	}
	
	@FunctionalInterface
	public static interface BlockingRunnable {
		void run() throws InterruptedException, ExecutionException, TimeoutException;
	}
	
	public static <T> T run(BlockingSupplier<T> supplier) {
		try {
			return supplier.get();
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static void run(BlockingRunnable runnable) {
		try {
			runnable.run();
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			throw new RuntimeException(e);
		}
	}
	
	@FunctionalInterface
	public static interface Parser<T> {
		T createParser(InputStream in) throws IOException, XmlPullParserException;
	}
	
	public static <T> T parse(Parser<T> parser, File file) {
		try {
			return parse(parser, new FileInputStream(file));
		} catch (FileNotFoundException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	public static <T> T parse(Parser<T> parser, InputStream in) {
		try {
			return parser.createParser(in);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (XmlPullParserException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static boolean isWindows() {
		return System.getProperty("os.name").startsWith("Windows");
	}
	
	public static <T> List<T> subList(List<T> original, int maxCount, Function<T,Boolean> filter) {
		if (original.isEmpty()) {
			return original;
		}
		var result = new ArrayList<T>(original.size());
		int count = 0;
		for (var item : original) {
			if (filter == null || (filter != null && filter.apply(item))) {
				result.add(item);
				count++;
				if (count >= maxCount) {
					break;
				}
			}
		}
		return result;
	}
	
	public static String sha256(File file) {
	    try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
	    	
	    	byte[] buffer= new byte[8192];
		    int count;
		    MessageDigest digest = MessageDigest.getInstance("SHA-256");
	    	
		    while ((count = bis.read(buffer)) > 0) {
		        digest.update(buffer, 0, count);
		    }
		    byte[] hash = digest.digest();
		    return new String(Base64.getEncoder().encode(hash));
		    
	    } catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}
}
