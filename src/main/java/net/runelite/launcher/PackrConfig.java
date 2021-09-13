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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.launcher.beans.Bootstrap;

@Slf4j
class PackrConfig
{
	// Update the packr vmargs
	static void updateLauncherArgs(Bootstrap bootstrap, Collection<String> extraJvmArgs)
	{
		File configFile = new File("config.json").getAbsoluteFile();

		// The AppImage mounts the packr directory on a readonly filesystem, so we can't update the vm args there
		if (!configFile.exists() || !configFile.canWrite())
		{
			return;
		}

		Gson gson = new GsonBuilder()
			.setPrettyPrinting()
			.create();
		Map config;
		try (FileInputStream fin = new FileInputStream(configFile))
		{
			config = gson.fromJson(new InputStreamReader(fin), Map.class);
		}
		catch (IOException e)
		{
			log.warn("error updating packr vm args!", e);
			return;
		}

		String[] argsArr = getArgs(bootstrap);
		if (argsArr == null || argsArr.length == 0)
		{
			log.warn("Launcher args are empty");
			return;
		}

		// Insert JVM arguments to config.json because some of them require restart
		List<String> args = new ArrayList<>();
		args.addAll(Arrays.asList(argsArr));
		args.addAll(extraJvmArgs);

		config.put("vmArgs", args);

		File tmpFile = new File("config.json.tmp");
		try
		{
			try (FileOutputStream fout = new FileOutputStream(tmpFile))
			{
				fout.getChannel().lock();
				try (PrintWriter writer = new PrintWriter(fout))
				{
					writer.write(gson.toJson(config));
				}
				// FileOutputStream.close() closes the associated channel, which frees the lock
			}

			try
			{
				Files.move(tmpFile.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException ex)
			{
				log.debug("atomic move not supported", ex);
				Files.move(tmpFile.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			log.warn("error updating packr vm args!", e);
			tmpFile.delete(); // best effort
		}
	}

	private static String[] getArgs(Bootstrap bootstrap)
	{
		switch (OS.getOs())
		{
			case Windows:
				if (bootstrap.getLauncherJvm11WindowsArguments() != null)
				{
					return bootstrap.getLauncherJvm11WindowsArguments();
				}
				return bootstrap.getLauncherJvm11Arguments();
			case MacOS:
				if (bootstrap.getLauncherJvm11MacArguments() != null)
				{
					return bootstrap.getLauncherJvm11MacArguments();
				}
				return bootstrap.getLauncherJvm11Arguments();

			default:
				return bootstrap.getLauncherJvm11Arguments();
		}
	}
}
