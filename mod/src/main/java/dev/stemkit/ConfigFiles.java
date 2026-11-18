package dev.stemkit;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Keeps the editable config copies (config/stemkit/trees.json, glyphs.json) in step with the mod.
 *
 * Every copy the mod writes carries "modVersion". When the config copy is missing, has no "modVersion", or was written
 * by an OLDER mod version than the one running, it is replaced by the built-in list of this jar (the old file is kept
 * next to it as *.bak). A copy written by this version or a newer one is left alone, so hand edits and
 * a publish script (which stamps the current version) survive restarts.
 */
final class ConfigFiles {
	private ConfigFiles() {}

	static String modVersion() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("stemkit")
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
	}

	/** Makes sure cfg exists and is at least as new as this mod; `resource` is the built-in copy inside the jar. */
	static void ensure(Path cfg, String resource) {
		String mine = modVersion();
		try {
			if (Files.exists(cfg)) {
				String theirs = null;
				try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
					JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
					if (o.has("modVersion")) theirs = o.get("modVersion").getAsString();
				} catch (Exception ex) {
					theirs = null;   // unreadable: replace it (the broken file is kept as .bak)
				}
				if (theirs != null && compare(theirs, mine) >= 0) return;
				Files.copy(cfg, cfg.resolveSibling(cfg.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
				com.mojang.logging.LogUtils.getLogger().info("[stemkit] {} was written by mod version {}; replacing it with the {} list (old copy: {}.bak)",
					cfg.getFileName(), theirs == null ? "unknown" : theirs, mine, cfg.getFileName());
			}
			try (InputStream def = ConfigFiles.class.getResourceAsStream(resource)) {
				if (def == null) return;
				JsonObject o = JsonParser.parseReader(new InputStreamReader(def, StandardCharsets.UTF_8)).getAsJsonObject();
				JsonObject out = new JsonObject();
				out.addProperty("modVersion", mine);
				for (var e : o.entrySet()) if (!e.getKey().equals("modVersion")) out.add(e.getKey(), e.getValue());
				Files.createDirectories(cfg.getParent());
				Files.writeString(cfg, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out), StandardCharsets.UTF_8);
			}
		} catch (Exception ex) {
			com.mojang.logging.LogUtils.getLogger().warn("[stemkit] could not refresh {}", cfg, ex);
		}
	}

	/** Compares dotted version strings numerically ("1.10.0" > "1.2.0"); non-numeric parts count as 0. */
	static int compare(String a, String b) {
		String[] x = a.split("[.+-]"), y = b.split("[.+-]");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
			if (p != q) return Integer.compare(p, q);
		}
		return 0;
	}

	private static int num(String s) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
