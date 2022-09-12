package de.eitco.mavenizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import de.eitco.mavenizer.analyze.Analyzer.JarHashes;

public class Util {
	private Util() {}
	
	public static final Path CURRENT_DIR = Paths.get(".");
	
	public static Optional<String> validateFileCanBeCreated(String pathString) {
		Path path = Paths.get(pathString);
		Path dir = path.toAbsolutePath().getParent();
		if (!dir.toFile().isDirectory()) {
			return Optional.of("Could not find parent directory '" + dir + "' to create file '" + path.getFileName() + "' in!");
		}
		if (path.toFile().exists()) {
			return Optional.of("File '" + path + "' already exists!");
		}
		return Optional.empty();
	}
	
	/**
	 * If argument contains paths to files, those files are returned.
	 * If argument contains paths to folders, all files (non-recursively) inside those folders are also returned.
	 * Argument can contain files and folders, but only files are returned.
	 */
	public static List<Path> getFiles(List<String> filesOrDirs, Predicate<Path> fileFilter) {
		var result = new ArrayList<Path>();
		for (var fileOrDirString : filesOrDirs) {
			Path fileOrDirAsPath = Paths.get(fileOrDirString);
			File fileOrDir = fileOrDirAsPath.toFile();
			if (fileOrDir.isDirectory()) {
				try (Stream<Path> files = Files.list(fileOrDirAsPath)) {
					result.addAll(files
							.filter(path -> path.toFile().isFile())
							.filter(fileFilter)
							.collect(Collectors.toList()));
			    } catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
			if (fileOrDir.isFile()) {
				result.add(fileOrDirAsPath);
			}
		}
		return result;
	}
	
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
	
	public static <T> List<T> subList(List<T> original, int maxCount, Predicate<T> filter) {
		if (original.isEmpty()) {
			return original;
		}
		var result = new ArrayList<T>(original.size());
		int count = 0;
		for (var item : original) {
			if (filter == null || filter.test(item)) {
				result.add(item);
				count++;
				if (count >= maxCount) {
					break;
				}
			}
		}
		return result;
	}
	
	public static JarHashes sha256(ZipInputStream zipIn) {
		try {
			var classesResult = new HashMap<Path, byte[]>();
			var emptyDigest = MessageDigest.getInstance("SHA-256");
			var jarDigest = (MessageDigest) emptyDigest.clone();
			
			ZipEntry entry;
			while ((entry = zipIn.getNextEntry()) != null) {
				String nameLower = entry.getName().toLowerCase();
				if (nameLower.endsWith(".class")) {
					var classDigest = (MessageDigest) emptyDigest.clone();
					updateDigests(zipIn, jarDigest, classDigest);
					classesResult.put(Paths.get(entry.getName()), classDigest.digest());
				} else {
					updateDigests(zipIn, jarDigest);
				}
			}
			
			byte[] jarHash = jarDigest.digest();
			var jarHashString = new String(Base64.getEncoder().encode(jarHash));
		    return new JarHashes(jarHashString, classesResult);
		    
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (CloneNotSupportedException e) {
			throw new RuntimeException(e);
		}
	}
	
	private static void updateDigests(InputStream in, MessageDigest... digests) {
		try {
	    	byte[] buffer= new byte[8192 * 4];
		    int count;
		    while ((count = in.read(buffer)) > 0) {
		    	for (var digest : digests) {
		    		digest.update(buffer, 0, count);
		    	}
		    }
	    } catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	public static JarHashes sha256(File compressedFile) {
	    try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(compressedFile))) {
	    	return sha256(zipIn);
	    } catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
