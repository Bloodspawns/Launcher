/*
 * Copyright (c) 2016-2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.launcher;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.archivepatcher.applier.FileByFileV1DeltaApplier;
import com.google.archivepatcher.shared.DefaultDeflateCompatibilityWindow;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.swing.SwingUtilities;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import lombok.extern.slf4j.Slf4j;
import net.runelite.launcher.beans.Artifact;
import net.runelite.launcher.beans.Bootstrap;
import net.runelite.launcher.beans.Diff;
import org.slf4j.LoggerFactory;

@Slf4j
public class Launcher
{
	private static final File RUNELITE_DIR = new File(System.getProperty("user.home"), ".runelite");
	public static final File LOGS_DIR = new File(RUNELITE_DIR, "logs");
	private static final File REPO_DIR = new File(RUNELITE_DIR, "bluerepo");
	private static final File CACHE_DIR = new File(RUNELITE_DIR + "/cache", "client");
	private static final File CLIENT_HASH_FILE = new File(CACHE_DIR, "client.serial");
	private static final File CLIENT_REPO_HASH_FILE = new File(CACHE_DIR, "client_repo.serial");
	private static final File EXTERNALS_DIR = new File(RUNELITE_DIR, "bexternalplugins");
	public static final File CRASH_FILES = new File(LOGS_DIR, "jvm_crash_pid_%p.log");
	private static final String BLUELITE_BOOTSTRAP_URL = "https://github.com/Bloodspawns/c0603cb96187d5c295173c5c90d3b389671964dab55056f913c3d86c3333300b/releases/download/1.0/bootstrap.json";
	private static final String USER_AGENT = "RuneLite/" + LauncherProperties.getVersion();

	public static void main(String[] args)
	{
		OptionParser parser = new OptionParser();
		parser.accepts("clientargs").withRequiredArg();
		parser.accepts("nojvm");
		parser.accepts("debug");
		parser.accepts("nodiff");
		parser.accepts("nouiscale");
		parser.accepts("insecure-skip-tls-verification");

		if (OS.getOs() == OS.OSType.MacOS)
		{
			parser.accepts("psn").withRequiredArg();
		}

		HardwareAccelerationMode defaultMode;
		switch (OS.getOs())
		{
			case Windows:
				defaultMode = HardwareAccelerationMode.DIRECTDRAW;
				break;
			case MacOS:
				defaultMode = HardwareAccelerationMode.OPENGL;
				break;
			case Linux:
			default:
				defaultMode = HardwareAccelerationMode.OFF;
				break;
		}

		// Create typed argument for the hardware acceleration mode
		final ArgumentAcceptingOptionSpec<HardwareAccelerationMode> mode = parser.accepts("mode")
			.withRequiredArg()
			.ofType(HardwareAccelerationMode.class)
			.defaultsTo(defaultMode);

		OptionSet options;
		try
		{
			options = parser.parse(args);
		}
		catch (OptionException ex)
		{
			log.error("unable to parse arguments", ex);
			throw ex;
		}

		final boolean nodiff = options.has("nodiff");
		final boolean insecureSkipTlsVerification = options.has("insecure-skip-tls-verification");

		// Setup debug
		final boolean isDebug = options.has("debug");
		LOGS_DIR.mkdirs();

		if (isDebug)
		{
			final Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
			logger.setLevel(Level.DEBUG);
		}

		try
		{
			SplashScreen.init();
			SplashScreen.stage(0, "Preparing", "Setting up environment");

			log.info("RuneLite Launcher version {}", LauncherProperties.getVersion());

			// Print out system info
			if (log.isDebugEnabled())
			{
				log.debug("Command line arguments: {}", String.join(" ", args));
				log.debug("Java Environment:");
				final Properties p = System.getProperties();
				final Enumeration keys = p.keys();

				while (keys.hasMoreElements())
				{
					final String key = (String) keys.nextElement();
					final String value = (String) p.get(key);
					log.debug("  {}: {}", key, value);
				}
			}

			// Get hardware acceleration mode
			final HardwareAccelerationMode hardwareAccelerationMode = options.valueOf(mode);
			log.info("Setting hardware acceleration to {}", hardwareAccelerationMode);

			// Enable hardware acceleration
			final List<String> extraJvmParams = hardwareAccelerationMode.toParams();

			// Always use IPv4 over IPv6
			extraJvmParams.add("-Djava.net.preferIPv4Stack=true");
			extraJvmParams.add("-Djava.net.preferIPv4Addresses=true");

			// Stream launcher version
			extraJvmParams.add("-D" + LauncherProperties.getVersionKey() + "=" + LauncherProperties.getVersion());

			if (options.has("nouiscale"))
			{
				extraJvmParams.add("-Dsun.java2d.uiScale=1");
			}


			if (insecureSkipTlsVerification)
			{
				extraJvmParams.add("-Drunelite.insecure-skip-tls-verification=true");
			}

			// Set all JVM params
			setJvmParams(extraJvmParams);

			// Set hs_err_pid location (do this after setJvmParams because it can't be set at runtime)
			log.debug("Setting JVM crash log location to {}", CRASH_FILES);
			extraJvmParams.add("-XX:ErrorFile=" + CRASH_FILES.getAbsolutePath());

			if (insecureSkipTlsVerification)
			{
				TrustManager trustManager = new X509TrustManager()
				{
					@Override
					public void checkClientTrusted(X509Certificate[] chain, String authType)
					{
					}

					@Override
					public void checkServerTrusted(X509Certificate[] chain, String authType)
					{
					}

					@Override
					public X509Certificate[] getAcceptedIssuers()
					{
						return null;
					}
				};

				SSLContext sc = SSLContext.getInstance("SSL");
				sc.init(null, new TrustManager[]{trustManager}, new SecureRandom());
				HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
				HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
			}

			SplashScreen.stage(.05, null, "Downloading bootstrap");
			Bootstrap bootstrap;
			try
			{
				bootstrap = getBootstrap();
			}
			catch (IOException | VerificationException | CertificateException | SignatureException | InvalidKeyException | NoSuchAlgorithmException ex)
			{
				log.error("error fetching bootstrap", ex);
				SwingUtilities.invokeLater(() -> FatalErrorDialog.showNetErrorWindow("downloading the bootstrap", ex));
				return;
			}

			try
			{
				Bootstrap bluestrap = getBlueBootstrap();

				bootstrap = mergeBootstraps(bluestrap, bootstrap);
			}
			catch (IOException | VerificationException | CertificateException | SignatureException | InvalidKeyException | NoSuchAlgorithmException ex)
			{
				log.error("error fetching bootstrap", ex);
				SwingUtilities.invokeLater(() -> FatalErrorDialog.showNetErrorWindow("downloading the bootstrap", ex));
				return;
			}
			String patchMinor = bootstrap.getPatchMinor();

			SplashScreen.stage(.10, null, "Tidying the cache");

			boolean launcherTooOld = bootstrap.getRequiredLauncherVersion() != null &&
				compareVersion(bootstrap.getRequiredLauncherVersion(), LauncherProperties.getVersion()) > 0;

			boolean jvmTooOld = false;
			try
			{
				if (bootstrap.getRequiredJVMVersion() != null)
				{
					jvmTooOld = Runtime.Version.parse(bootstrap.getRequiredJVMVersion())
						.compareTo(Runtime.version()) > 0;
				}
			}
			catch (IllegalArgumentException e)
			{
				log.warn("Unable to parse bootstrap version", e);
			}

			boolean nojvm = "true".equals(System.getProperty("runelite.launcher.nojvm"));

			if (launcherTooOld || (nojvm && jvmTooOld))
			{
				SwingUtilities.invokeLater(() ->
					new FatalErrorDialog("Your launcher is to old to start RuneLite. Please download and install a more " +
						"recent one from RuneLite.net.")
						.addButton("RuneLite.net", () -> LinkBrowser.browse(LauncherProperties.getDownloadLink()))
						.open());
				return;
			}
			if (jvmTooOld)
			{
				Bootstrap finalBootstrap = bootstrap;
				SwingUtilities.invokeLater(() ->
					new FatalErrorDialog("Your Java installation is too old. RuneLite now requires Java " +
						finalBootstrap.getRequiredJVMVersion() + " to run. You can get a platform specific version from RuneLite.net," +
						" or install a newer version of Java.")
						.addButton("RuneLite.net", () -> LinkBrowser.browse(LauncherProperties.getDownloadLink()))
						.open());
				return;
			}

			// update packr vmargs. The only extra vmargs we need to write to disk are the ones which cannot be set
			// at runtime, which currently is just the vm errorfile.
			PackrConfig.updateLauncherArgs(bootstrap, Collections.singleton("-XX:ErrorFile=" + CRASH_FILES.getAbsolutePath()));

			REPO_DIR.mkdirs();
			CACHE_DIR.mkdirs();

			// Clean out old artifacts from the repository
			clean(bootstrap.getArtifacts());

			try
			{
				download(bootstrap, nodiff);
			}
			catch (IOException ex)
			{
				log.error("unable to download artifacts", ex);
				SwingUtilities.invokeLater(() -> FatalErrorDialog.showNetErrorWindow("downloading the client", ex));
				return;
			}

			List<File> results = new ArrayList<>();

			File[] externals = EXTERNALS_DIR.listFiles();
			if (externals != null)
			{
				results.addAll(Arrays.asList(externals));
			}

			results.addAll(Arrays.stream(bootstrap.getArtifacts())
					.map(dep -> new File(REPO_DIR, dep.getName()))
					.collect(Collectors.toList()));

			String patchName = "";
			for (File file : results)
			{
				if (isClientPatch(file.getName()))
				{
					patchName = file.getName();
					patchName = patchName.substring("client-patch-".length());
					patchName = patchName.substring(0, patchName.length() - ".jar".length());
				}
			}
			if (patchName.equals(patchMinor) && results.stream().anyMatch(s -> isBluePatch(s.getName())))
			{
				results = results.stream().filter(s -> !isClientPatch(s.getName())).collect(Collectors.toList());
			}
			else
			{
				results = results.stream().filter(s -> !isBluePatch(s.getName())).collect(Collectors.toList());
			}

			SplashScreen.stage(.80, null, "Verifying");
			try
			{
				verifyJarHashes(bootstrap.getArtifacts());
			}
			catch (VerificationException ex)
			{
				log.error("Unable to verify artifacts", ex);
				SwingUtilities.invokeLater(() -> FatalErrorDialog.showNetErrorWindow("verifying downloaded files", ex));
				return;
			}

			final Collection<String> clientArgs = getClientArgs(options);

			if (log.isDebugEnabled())
			{
				clientArgs.add("--debug");
			}

			SplashScreen.stage(.90, "Starting the client", "");

			// packr doesn't let us specify command line arguments
			if ((nojvm || options.has("nojvm")) && !options.has("nouiscale"))
			{
				try
				{
					log.info("Using reflection launcher");
					ReflectionLauncher.launch(results, clientArgs);
				}
				catch (MalformedURLException ex)
				{
					log.error("unable to launch client", ex);
				}
			}
			else
			{
				try
				{
					log.info("Using JvmLauncher launcher");
					JvmLauncher.launch(bootstrap, results, clientArgs, extraJvmParams);
				}
				catch (IOException ex)
				{
					log.error("unable to launch client", ex);
				}
			}
		}
		catch (Exception e)
		{
			log.error("Failure during startup", e);
			SwingUtilities.invokeLater(() ->
				new FatalErrorDialog("RuneLite has encountered an unexpected error during startup.")
					.open());
		}
		catch (Error e)
		{
			// packr seems to eat exceptions thrown out of main, so at least try to log it
			log.error("Failure during startup", e);
			throw e;
		}
		finally
		{
			SplashScreen.stop();
		}
	}

	private static void setJvmParams(final Collection<String> params)
	{
		for (String param : params)
		{
			final String[] split = param.replace("-D", "").split("=");
			System.setProperty(split[0], split[1]);
		}
	}

	private static Bootstrap getBootstrap() throws IOException, CertificateException, NoSuchAlgorithmException, InvalidKeyException, SignatureException, VerificationException
	{
		URL u = new URL(LauncherProperties.getBootstrap());
		URL signatureUrl = new URL(LauncherProperties.getBootstrapSig());

		URLConnection conn = u.openConnection();
		URLConnection signatureConn = signatureUrl.openConnection();

		conn.setRequestProperty("User-Agent", USER_AGENT);
		signatureConn.setRequestProperty("User-Agent", USER_AGENT);

		try (InputStream i = conn.getInputStream();
			InputStream signatureIn = signatureConn.getInputStream())
		{
			byte[] bytes = ByteStreams.toByteArray(i);
			byte[] signature = ByteStreams.toByteArray(signatureIn);

			Certificate certificate = getCertificate();
			Signature s = Signature.getInstance("SHA256withRSA");
			s.initVerify(certificate);
			s.update(bytes);

			if (!s.verify(signature))
			{
				throw new VerificationException("Unable to verify bootstrap signature");
			}

			Gson g = new Gson();
			return g.fromJson(new InputStreamReader(new ByteArrayInputStream(bytes)), Bootstrap.class);
		}
	}

	private static Bootstrap getBlueBootstrap() throws IOException, CertificateException, NoSuchAlgorithmException, InvalidKeyException, SignatureException, VerificationException
	{
		URL u = new URL(BLUELITE_BOOTSTRAP_URL);

		URLConnection conn = u.openConnection();

		conn.setRequestProperty("User-Agent", USER_AGENT);

		try (InputStream i = conn.getInputStream())
		{
			byte[] bytes = ByteStreams.toByteArray(i);

			Gson g = new Gson();
			return g.fromJson(new InputStreamReader(new ByteArrayInputStream(bytes)), Bootstrap.class);
		}
	}

	private static Collection<String> getClientArgs(OptionSet options)
	{
		String clientArgs = System.getenv("RUNELITE_ARGS");
		if (options.has("clientargs"))
		{
			clientArgs = (String) options.valueOf("clientargs");
		}
		return !Strings.isNullOrEmpty(clientArgs)
			? new ArrayList<>(Splitter.on(' ').omitEmptyStrings().trimResults().splitToList(clientArgs))
			: new ArrayList<>();
	}

	private static void download(Bootstrap bootstrap, boolean nodiff) throws IOException
	{
		Artifact[] artifacts = bootstrap.getArtifacts();
		List<Artifact> toDownload = new ArrayList<>(artifacts.length);
		Map<Artifact, Diff> diffs = new HashMap<>();
		int totalDownloadBytes = 0;
		final boolean isCompatible = new DefaultDeflateCompatibilityWindow().isCompatible();

		if (!isCompatible && !nodiff)
		{
			log.debug("System zlib is not compatible with archive-patcher; not using diffs");
			nodiff = true;
		}

		for (Artifact artifact : artifacts)
		{
			File dest = new File(REPO_DIR, artifact.getName());

			String hash;
			try
			{
				hash = hash(dest);
			}
			catch (FileNotFoundException ex)
			{
				hash = null;
			}

			if (isClient(artifact.getName()))
			{
				if (CLIENT_HASH_FILE.exists() && CLIENT_REPO_HASH_FILE.exists() && new File(REPO_DIR, artifact.getName()).exists())
				{
					boolean match1;
					boolean match2;
					try (FileInputStream fos = new FileInputStream(CLIENT_HASH_FILE))
					{
						byte[] buffer = fos.readAllBytes();
						hash = new String(buffer, StandardCharsets.UTF_8);

						match1 = Objects.equals(hash, artifact.getHash());
					}

					try (FileInputStream fos = new FileInputStream(CLIENT_REPO_HASH_FILE))
					{
						byte[] buffer = fos.readAllBytes();
						hash = new String(buffer, StandardCharsets.UTF_8);
						String hash1 = hash(new File(REPO_DIR, artifact.getName()));

						match2 = Objects.equals(hash, hash1);
					}

					if (match1 && match2)
					{
						log.debug("Hash for {} up to date", artifact.getName());
						continue;
					}
				}
			}
			else if (Objects.equals(hash, artifact.getHash()))
			{
				log.debug("Hash for {} up to date", artifact.getName());
				continue;
			}

			int downloadSize = artifact.getSize();

			// See if there is a diff available
			if (!nodiff && artifact.getDiffs() != null)
			{
				for (Diff diff : artifact.getDiffs())
				{
					File old = new File(REPO_DIR, diff.getFrom());

					String oldhash;
					try
					{
						oldhash = hash(old);
					}
					catch (FileNotFoundException ex)
					{
						oldhash = null;
					}

					// Check if old file is valid
					if (diff.getFromHash().equals(oldhash))
					{
						diffs.put(artifact, diff);
						downloadSize = diff.getSize();
					}
				}
			}

			toDownload.add(artifact);
			totalDownloadBytes += downloadSize;
		}

		final double START_PROGRESS = .15;
		int downloaded = 0;
		SplashScreen.stage(START_PROGRESS, "Downloading", "");

		for (Artifact artifact : toDownload)
		{
			File dest = new File(REPO_DIR, artifact.getName());
			final int total = downloaded;

			// Check if there is a diff we can download instead
			Diff diff = diffs.get(artifact);
			if (diff != null)
			{
				log.debug("Downloading diff {}", diff.getName());

				try
				{
					final int totalBytes = totalDownloadBytes;
					final byte[] patch = download(diff.getPath(), diff.getHash(), (completed) ->
						SplashScreen.stage(START_PROGRESS, .80, null, diff.getName(), total + completed, totalBytes, true));
					downloaded += diff.getSize();
					File old = new File(REPO_DIR, diff.getFrom());
					try (InputStream patchStream = new GZIPInputStream(new ByteArrayInputStream(patch));
						FileOutputStream fout = new FileOutputStream(dest))
					{
						new FileByFileV1DeltaApplier().applyDelta(old, patchStream, fout);
					}

					continue;
				}
				catch (IOException | VerificationException e)
				{
					log.warn("unable to download patch {}", diff.getName(), e);
					// Fall through and try downloading the full artifact

					// Adjust the download size for the difference
					totalDownloadBytes -= diff.getSize();
					totalDownloadBytes += artifact.getSize();
				}
			}

			log.debug("Downloading {}", artifact.getName());

			try
			{
				final int totalBytes = totalDownloadBytes;
				final byte[] jar = download(artifact.getPath(), artifact.getHash(), (completed) ->
					SplashScreen.stage(START_PROGRESS, .80, null, artifact.getName(), total + completed, totalBytes, true));
				downloaded += artifact.getSize();
				String[] blacklist = bootstrap.getRemoves();
				try (FileOutputStream fout = new FileOutputStream(dest))
				{
					if (isClient(artifact.getName()))
					{
						ByteArrayInputStream bais = new ByteArrayInputStream(jar);
						JarInputStream jis = new JarInputStream(bais);
						JarOutputStream jos = new JarOutputStream(fout);

						JarEntry je = jis.getNextJarEntry();
						byte[] buf = new byte[16384];
						while (je != null)
						{
							JarEntry finalJe = je;
							if (Arrays.stream(blacklist).noneMatch(s -> finalJe.getName().startsWith(s)))
							{
								jos.putNextEntry(je);

								int read = jis.read(buf);
								while (read != -1)
								{
									jos.write(buf, 0, read);
									read = jis.read(buf);
								}
							}

							je = jis.getNextJarEntry();
						}

						bais.close();
						jis.close();
						jos.close();
						fout.close();

						FileOutputStream foutHash = new FileOutputStream(CLIENT_HASH_FILE);
						foutHash.write(artifact.getHash().getBytes(StandardCharsets.UTF_8));
						foutHash.close();

						String hash = hash(dest);
						FileOutputStream foutHash1 = new FileOutputStream(CLIENT_REPO_HASH_FILE);
						foutHash1.write(hash.getBytes(StandardCharsets.UTF_8));
						foutHash1.close();
					}
					else
					{
						fout.write(jar);
					}
				}
			}
			catch (VerificationException e)
			{
				log.warn("unable to verify jar {}", artifact.getName(), e);
			}
		}
	}

	private static Bootstrap mergeBootstraps(Bootstrap b1, Bootstrap b2)
	{
		Bootstrap breturn = new Bootstrap();

		breturn.setPatchMinor(shallowConcat(b1.getPatchMinor(), b2.getPatchMinor()));
		breturn.setRequiredJVMVersion(shallowConcat(b1.getRequiredJVMVersion(), b2.getRequiredJVMVersion()));
		breturn.setRequiredLauncherVersion(shallowConcat(b1.getRequiredLauncherVersion(), b2.getRequiredLauncherVersion()));

		breturn.setArtifacts(shallowCopyArtifact(b1.getArtifacts(), b2.getArtifacts()));
		breturn.setClientJvm9Arguments(shallowCopyString(b1.getClientJvm9Arguments(), b2.getClientJvm9Arguments()));
		breturn.setLauncherJvm11Arguments(shallowCopyString(b1.getLauncherJvm11Arguments(), b2.getLauncherJvm11Arguments()));
		breturn.setLauncherJvm11MacArguments(shallowCopyString(b1.getLauncherJvm11MacArguments(), b2.getLauncherJvm11MacArguments()));
		breturn.setLauncherJvm11WindowsArguments(shallowCopyString(b1.getLauncherJvm11WindowsArguments(), b2.getLauncherJvm11WindowsArguments()));
		breturn.setRemoves(shallowCopyString(b1.getRemoves(), b2.getRemoves()));
		return breturn;
	}

	private static String[] shallowCopyString(String[] first, String[] second)
	{
		if (first == null)
		{
			return second;
		}
		else if (second == null)
		{
			return first;
		}
		String[] both = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, both, first.length, second.length);
		return both;
	}

	private static Artifact[] shallowCopyArtifact(Artifact[] first, Artifact[] second)
	{
		if (first == null)
		{
			return second;
		}
		else if (second == null)
		{
			return first;
		}
		Artifact[] both = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, both, first.length, second.length);
		return both;
	}

	private static String shallowConcat(String s1, String s2)
	{
		if (s1 == null)
		{
			return s2;
		}
		if (s2 == null)
		{
			return s1;
		}
		return s1 + s2;
	}

	private static boolean isPlugins(String name)
	{
		return name.matches("^plugins-([0-9]*\\.)*[0-9]*\\.jar$");
	}

	private static boolean isBluePatch(String name)
	{
		return name.matches("^blue-patch-?([0-9]*\\.)*[0-9]*\\.jar$");
	}

	private static boolean isClientPatch(String name)
	{
		return name.matches("^client-patch-([0-9]*\\.)*[0-9]*\\.jar$");
	}

	private static boolean isClient(String name)
	{
		return name.matches("^client-([0-9]*\\.)*[0-9]*\\.jar$");
	}

	private static void clean(Artifact[] artifacts)
	{
		File[] existingFiles = REPO_DIR.listFiles();

		if (existingFiles == null)
		{
			return;
		}

		Set<String> artifactNames = new HashSet<>();
		for (Artifact artifact : artifacts)
		{
			artifactNames.add(artifact.getName());
			if (artifact.getDiffs() != null)
			{
				// Keep around the old files which diffs are from
				for (Diff diff : artifact.getDiffs())
				{
					artifactNames.add(diff.getFrom());
				}
			}
		}

		for (File file : existingFiles)
		{
			if (file.isFile() && !artifactNames.contains(file.getName()))
			{
				if (file.delete())
				{
					log.debug("Deleted old artifact {}", file);
				}
				else
				{
					log.warn("Unable to delete old artifact {}", file);
				}
			}
		}
	}

	private static void verifyJarHashes(Artifact[] artifacts) throws VerificationException
	{
		for (Artifact artifact : artifacts)
		{
			String expectedHash = artifact.getHash();
			String fileHash = "";
			try
			{
				if (isClient(artifact.getName()))
				{
					try (FileInputStream fos = new FileInputStream(CLIENT_HASH_FILE))
					{
						byte[] buffer = fos.readAllBytes();
						fileHash = new String(buffer, StandardCharsets.UTF_8);
					}
				}
				else
				{
					fileHash = hash(new File(REPO_DIR, artifact.getName()));
				}
			}
			catch (IOException e)
			{
				throw new VerificationException("unable to hash file", e);
			}

			if (!fileHash.equals(expectedHash))
			{
				log.warn("Expected {} for {} but got {}", expectedHash, artifact.getName(), fileHash);
				throw new VerificationException("Expected " + expectedHash + " for " + artifact.getName() + " but got " + fileHash);
			}

			log.info("Verified hash of {}", artifact.getName());
		}
	}

	private static String hash(File file) throws IOException
	{
		HashFunction sha256 = Hashing.sha256();
		return Files.asByteSource(file).hash(sha256).toString();
	}

	private static Certificate getCertificate() throws CertificateException
	{
		CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
		Certificate certificate = certFactory.generateCertificate(Launcher.class.getResourceAsStream("runelite.crt"));
		return certificate;
	}

	@VisibleForTesting
	static int compareVersion(String a, String b)
	{
		Pattern tok = Pattern.compile("[^0-9a-zA-Z]");
		return Arrays.compare(tok.split(a), tok.split(b), (x, y) ->
		{
			Integer ix = null;
			try
			{
				ix = Integer.parseInt(x);
			}
			catch (NumberFormatException e)
			{
			}

			Integer iy = null;
			try
			{
				iy = Integer.parseInt(y);
			}
			catch (NumberFormatException e)
			{
			}

			if (ix == null && iy == null)
			{
				return x.compareToIgnoreCase(y);
			}

			if (ix == null)
			{
				return -1;
			}
			if (iy == null)
			{
				return 1;
			}

			if (ix > iy)
			{
				return 1;
			}
			if (ix < iy)
			{
				return -1;
			}

			return 0;
		});
	}

	private static byte[] download(String path, String hash, IntConsumer progress) throws IOException, VerificationException
	{
		HashFunction hashFunction = Hashing.sha256();
		Hasher hasher = hashFunction.newHasher();
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

		URL url = new URL(path);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("User-Agent", USER_AGENT);
		conn.getResponseCode();

		InputStream err = conn.getErrorStream();
		if (err != null)
		{
			err.close();
			throw new IOException("Unable to download " + path + " - " + conn.getResponseMessage());
		}

		int downloaded = 0;
		try (InputStream in = conn.getInputStream())
		{
			int i;
			byte[] buffer = new byte[1024 * 1024];
			while ((i = in.read(buffer)) != -1)
			{
				byteArrayOutputStream.write(buffer, 0, i);
				hasher.putBytes(buffer, 0, i);
				downloaded += i;
				progress.accept(downloaded);
			}
		}

		HashCode hashCode = hasher.hash();
		if (!hash.equals(hashCode.toString()))
		{
			throw new VerificationException("Unable to verify resource " + path + " - expected " + hash + " got " + hashCode.toString());
		}

		return byteArrayOutputStream.toByteArray();
	}
}
