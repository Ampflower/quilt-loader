/*
 * Copyright 2016 FabricMC
 * Copyright 2022 QuiltMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.quiltmc.loader.impl;

import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.Opcodes;
import org.quiltmc.loader.api.LanguageAdapter;
import org.quiltmc.loader.api.MappingResolver;
import org.quiltmc.loader.api.ModContainer.BasicSourceType;
import org.quiltmc.loader.api.ModDependency;
import org.quiltmc.loader.api.entrypoint.EntrypointContainer;
import org.quiltmc.loader.api.plugin.ModContainerExt;
import org.quiltmc.loader.api.plugin.ModMetadataExt;
import org.quiltmc.loader.api.plugin.ModMetadataExt.ProvidedMod;
import org.quiltmc.loader.api.plugin.gui.PluginGuiTreeNode.WarningLevel;
import org.quiltmc.loader.api.plugin.gui.QuiltLoaderText;
import org.quiltmc.loader.api.plugin.solver.LoadOption;
import org.quiltmc.loader.api.plugin.solver.ModLoadOption;
import org.quiltmc.loader.api.plugin.solver.ModSolveResult;
import org.quiltmc.loader.api.plugin.solver.ModSolveResult.SpecificLoadOptionResult;
import org.quiltmc.loader.impl.discovery.ClasspathModCandidateFinder;
import org.quiltmc.loader.impl.discovery.ModResolutionException;
import org.quiltmc.loader.impl.discovery.ModSolvingError;
import org.quiltmc.loader.impl.entrypoint.EntrypointStorage;
import org.quiltmc.loader.impl.entrypoint.EntrypointUtils;
import org.quiltmc.loader.impl.filesystem.QuiltJoinedFileSystem;
import org.quiltmc.loader.impl.filesystem.QuiltJoinedPath;
import org.quiltmc.loader.impl.game.GameProvider;
import org.quiltmc.loader.impl.gui.QuiltGuiEntry;
import org.quiltmc.loader.impl.gui.QuiltJsonGui;
import org.quiltmc.loader.impl.gui.QuiltJsonGui.QuiltBasicButtonAction;
import org.quiltmc.loader.impl.launch.common.QuiltCodeSource;
import org.quiltmc.loader.impl.launch.common.QuiltLauncher;
import org.quiltmc.loader.impl.launch.common.QuiltLauncherBase;
import org.quiltmc.loader.impl.launch.common.QuiltMixinBootstrap;
import org.quiltmc.loader.impl.launch.knot.Knot;
import org.quiltmc.loader.impl.metadata.FabricLoaderModMetadata;
import org.quiltmc.loader.impl.metadata.qmj.AdapterLoadableClassEntry;
import org.quiltmc.loader.impl.metadata.qmj.InternalModMetadata;
import org.quiltmc.loader.impl.plugin.QuiltPluginErrorImpl;
import org.quiltmc.loader.impl.plugin.QuiltPluginManagerImpl;
import org.quiltmc.loader.impl.plugin.fabric.FabricModOption;
import org.quiltmc.loader.impl.plugin.gui.GuiManagerImpl;
import org.quiltmc.loader.impl.report.QuiltReport.CrashReportSaveFailed;
import org.quiltmc.loader.impl.report.QuiltReportedError;
import org.quiltmc.loader.impl.solver.ModSolveResultImpl;
import org.quiltmc.loader.impl.transformer.TransformCache;
import org.quiltmc.loader.impl.util.Arguments;
import org.quiltmc.loader.impl.util.DefaultLanguageAdapter;
import org.quiltmc.loader.impl.util.FileSystemUtil;
import org.quiltmc.loader.impl.util.FileSystemUtil.FileSystemDelegate;
import org.quiltmc.loader.impl.util.ModLanguageAdapter;
import org.quiltmc.loader.impl.util.SystemProperties;
import org.quiltmc.loader.impl.util.log.Log;
import org.quiltmc.loader.impl.util.log.LogCategory;
import org.spongepowered.asm.mixin.FabricUtil;

import net.fabricmc.loader.api.ObjectShare;

import net.fabricmc.accesswidener.AccessWidener;
import net.fabricmc.accesswidener.AccessWidenerReader;
import net.fabricmc.api.EnvType;

public final class QuiltLoaderImpl {
	public static final QuiltLoaderImpl INSTANCE = InitHelper.get();

	public static final int ASM_VERSION = Opcodes.ASM9;

	public static final String VERSION = "0.18.1-beta.25";
	public static final String MOD_ID = "quilt_loader";
	public static final String DEFAULT_MODS_DIR = "mods";
	public static final String DEFAULT_CONFIG_DIR = "config";

	public static final String CACHE_DIR_NAME = ".quilt"; // relative to game dir
	private static final String PROCESSED_MODS_DIR_NAME = "processedMods"; // relative to cache dir
	public static final String REMAPPED_JARS_DIR_NAME = "remappedJars"; // relative to cache dir
	private static final String TMP_DIR_NAME = "tmp"; // relative to cache dir

	protected final Map<String, ModContainerExt> modMap = new HashMap<>();

	protected List<ModContainerExt> mods = new ArrayList<>();

	private final Map<String, LanguageAdapter> adapterMap = new HashMap<>();
	private final EntrypointStorage entrypointStorage = new EntrypointStorage();
	private final AccessWidener accessWidener = new AccessWidener();

	private final ObjectShare objectShare = new ObjectShareImpl();

	private boolean frozen = false;

	private Object gameInstance;

	private MappingResolver mappingResolver;
	private GameProvider provider;
	/** The value of {@link Arguments#ADD_MODS}. This must be stored since we remove it before launching the game. */
	private String argumentModsList;
	private Path gameDir;
	private Path configDir;
	private Path modsDir;

	/** Destination folder for {@link #copiedToJarMods}. */
	private File temporaryCopiedJarFolder;

	/** Stores every mod which has been copied into a temporary jar file: see {@link #shouldCopyToJar(ModLoadOption)}
	 * and {@link #copyToJar(ModLoadOption, Path)}. */
	private final Map<String, File> copiedToJarMods = new HashMap<>();

	protected QuiltLoaderImpl() {
	}

	/**
	 * Freeze the FabricLoader, preventing additional mods from being loaded.
	 */
	public void freeze() {
		if (frozen) {
			throw new IllegalStateException("Already frozen!");
		}

		frozen = true;
		finishModLoading();
	}

	public GameProvider getGameProvider() {
		if (provider == null) throw new IllegalStateException("game provider not set (yet)");

		return provider;
	}

	public GameProvider tryGetGameProvider() {
		return provider;
	}

	public void setGameProvider(GameProvider provider) {
		this.provider = provider;

		setGameDir(provider.getLaunchDirectory());
		argumentModsList = provider.getArguments().remove(Arguments.ADD_MODS);
	}

	private void setGameDir(Path gameDir) {
		this.gameDir = gameDir;
		String configDir = System.getProperty(SystemProperties.CONFIG_DIRECTORY);
		this.configDir = gameDir.resolve((configDir == null || configDir.isEmpty()) ? DEFAULT_CONFIG_DIR : configDir);
		initializeModsDir(gameDir);
	}

	private void initializeModsDir(Path gameDir) {
		String modsDir = System.getProperty(SystemProperties.MODS_DIRECTORY);
		this.modsDir = gameDir.resolve((modsDir == null || modsDir.isEmpty()) ? DEFAULT_MODS_DIR : modsDir);
	}

	public String getAdditionalModsArgument() {
		return argumentModsList;
	}

	public Object getGameInstance() {
		return gameInstance;
	}

	public EnvType getEnvironmentType() {
		return QuiltLauncherBase.getLauncher().getEnvironmentType();
	}

	/**
	 * @return The game instance's root directory.
	 */
	public Path getGameDir() {
		return gameDir;
	}

	/**
	 * @return The game instance's configuration directory.
	 */
	public Path getConfigDir() {
		if (configDir == null) {
			// May be null during tests
			// If this is in production then things are about to go very wrong.
			return null;
		}

		if (!Files.exists(configDir)) {
			try {
				Files.createDirectories(configDir);
			} catch (IOException e) {
				throw new RuntimeException(String.format("Failed to create config directory at '%s'", configDir), e);
			}
		}

		return configDir;
	}

	public Path getModsDir() {
		// modsDir should be initialized before this method is ever called, this acts as a very special failsafe
		if (modsDir == null) {
			initializeModsDir(gameDir);
		}

		if (!Files.exists(modsDir)) {
			try {
				Files.createDirectories(modsDir);
			} catch (IOException e) {
				throw new RuntimeException(String.format("Failed to create mods directory at '%s'", modsDir), e);
			}
		}
		return modsDir;
	}

	public void load() {
		if (provider == null) throw new IllegalStateException("game provider not set");
		if (frozen) throw new IllegalStateException("Frozen - cannot load additional mods!");

		try {
			setup();
		} catch (ModResolutionException exception) {
			throw new FormattedException("Incompatible mod set!", exception);
		}
	}

	private void setup() throws ModResolutionException {

		ModSolveResult result = runPlugins();

		SpecificLoadOptionResult<LoadOption> spec = result.getResult(LoadOption.class);

		// Debugging
		if (Boolean.getBoolean(SystemProperties.DEBUG_MOD_SOLVING)) {
			for (LoadOption op : spec.getOptions()) {
				if (spec.isPresent(op)) {
					Log.info(LogCategory.GENERAL, " + " + op);
				}
			}

			for (LoadOption op : spec.getOptions()) {
				if (!spec.isPresent(op)) {
					Log.info(LogCategory.GENERAL, " - " + op);
				}
			}
		}

		List<ModLoadOption> modList = new ArrayList<>(result.directMods().values());

		performMixinReordering(modList);
		performLoadLateReordering(modList);

		Path transformCacheFile = getGameDir().resolve(CACHE_DIR_NAME).resolve("transform-cache.zip");
		TransformCache.populateTransformBundle(transformCacheFile, modList, result);
		Path transformedModBundle;
		try {
			transformedModBundle = FileSystemUtil.getJarFileSystem(transformCacheFile, false).get().getPath("/");
		} catch (IOException e) {
			throw new RuntimeException(e); // TODO
		}

		Set<String> modsToCopy = new HashSet<>();
		String jarCopiedMods = System.getProperty(SystemProperties.JAR_COPIED_MODS);
		if (jarCopiedMods != null) {
			for (String id : jarCopiedMods.split(",")) {
				modsToCopy.add(id);
			}
		}

		for (ModLoadOption modOption : modList) {
			Path resourceRoot;

			if (!modOption.needsChasmTransforming() && modOption.namespaceMappingFrom() == null) {
				resourceRoot = modOption.resourceRoot();
			} else {
				Path modTransformed = transformedModBundle.resolve(modOption.id() + "/");
				Path excluded = transformedModBundle.resolve(modOption.id() + ".removed");

				if (Files.exists(excluded)) {
					throw new Error("// TODO: Implement pre-transform file removal!");
				} else if (!Files.isDirectory(modTransformed)) {
					resourceRoot = modOption.resourceRoot();
				} else {
					List<Path> paths = new ArrayList<>();

					paths.add(modTransformed);

					/* Single path optimisation disabled since
					 * URLClassPath can't handle loading folders from inside a zip.
					 * We can re-enable it if we either move to our own classloader
					 * or create the "cached filemap" filesystem. */

					// if (paths.size() == 1) {
					// resourceRoot = paths.get(0);
					// } else {
					resourceRoot = new QuiltJoinedFileSystem("final-mod-" + modOption.id(), paths).getRoot();
					// }
				}
			}

			if (modsToCopy.contains(modOption.id()) || shouldCopyToJar(modOption)) {
				resourceRoot = copyToJar(modOption, resourceRoot);
			}

			addMod(modOption.convertToMod(resourceRoot));
		}

		int count = mods.size();
		Log.info(LogCategory.GENERAL, "Loading %d mod%s:%n%s", count, count != 1 ? "s" : "", createModTable());
	}

	private boolean shouldCopyToJar(ModLoadOption mod) {
		String id = mod.id();
		if (id.contains("yung")) {
			// YUNGs mods use reflections
			// which *require* the class files are loaded directly from .jar files :|
			return true;
		}
		if ("charm".equals(id) /* Add version check here for if/when charm doesn't need this */) {
			// Charm also (currently) requires the mod files are in .jars directly.
			return true;
		}
		for (ModDependency dep : mod.metadata().depends()) {
			if (dep instanceof ModDependency.Only) {
				String depId = ((ModDependency.Only) dep).id().id();
				if (depId.contains("yung")) {
					return true;
				}
			}
		}
		return false;
	}

	private Path copyToJar(ModLoadOption modOption, final Path resourceRoot) throws Error {
		try {
			if (temporaryCopiedJarFolder == null) {
				temporaryCopiedJarFolder = Files.createTempDirectory("quilt-loader").toFile();
				temporaryCopiedJarFolder.deleteOnExit();
			}
			File file = File.createTempFile(modOption.id(), ".jar", temporaryCopiedJarFolder);
			Log.info(LogCategory.GENERAL, "Copying " + modOption.id() + " to a temporary jar file " + file);
			file.deleteOnExit();
			try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
				List<Path> list = Files.walk(resourceRoot).collect(Collectors.toList());
				for (Path path : list) {
					String pathStr = path.toString();
					if (pathStr.startsWith("/")) {
						pathStr = pathStr.substring(1);
					}
					if (Files.isDirectory(path)) {
						zip.putNextEntry(new ZipEntry(pathStr + "/"));
					} else {
						zip.putNextEntry(new ZipEntry(pathStr));
						zip.write(Files.readAllBytes(path));
					}
					zip.closeEntry();
				}
			}

			copiedToJarMods.put(modOption.id(), file);

			FileSystem fs = FileSystems.newFileSystem(file.toPath(), (ClassLoader) null);
			return fs.getPath("/");
		} catch (IOException e) {
			throw new Error("// TODO: Failed to copy to jar!");
		}
	}

	private ModSolveResult runPlugins() {
		QuiltLoaderConfig config = new QuiltLoaderConfig(getConfigDir().resolve("quilt-loader.txt"));
		QuiltPluginManagerImpl plugins = new QuiltPluginManagerImpl(getGameDir(), getConfigDir(), getModsDir(), provider, config);

		Path crashReportFile = null;
		String fullCrashText = null;

		try {
			ModSolveResultImpl result = plugins.run(true);

			if ((provider != null && !provider.canOpenErrorGui()) || GraphicsEnvironment.isHeadless()) {
				return result;
			}

			boolean dev = isDevelopmentEnvironment();
			boolean show = config.alwaysShowModStateWindow;

			if (!dev && !show) {
				return result;
			}

			boolean anyWarnings = false;

			if (plugins.guiFileRoot.getMaximumLevel().ordinal() <= WarningLevel.WARN.ordinal()) {
				anyWarnings = true;
			}

			if (plugins.guiModsRoot.getMaximumLevel().ordinal() <= WarningLevel.WARN.ordinal()) {
				anyWarnings = true;
			}

			if (!show && dev && !anyWarnings) {
				return result;
			}

			final String msg;
			if (anyWarnings) {
				int count = plugins.guiModsRoot.countOf(WarningLevel.WARN)//
					+ plugins.guiFileRoot.countOf(WarningLevel.WARN);
				msg = QuiltLoaderText.translate("msg.load_state.warns", count).toString();
			} else {
				msg = QuiltLoaderText.translate("msg.load_state").toString();
			}

			QuiltJsonGui tree = new QuiltJsonGui("Quilt Loader " + VERSION, msg);
			plugins.guiManager.putIcons(tree);
			QuiltJsonGui.QuiltJsonGuiTreeTab tab = tree.addTab("Files");
			plugins.guiFileRoot.text(QuiltLoaderText.translate("tab.file_list"));
			plugins.guiFileRoot.toNode(tab.node, false);

			QuiltJsonGui.QuiltJsonGuiTreeTab tab2 = tree.addTab("Mods");
			plugins.guiModsRoot.text(QuiltLoaderText.translate("tab.mod_list"));
			plugins.guiModsRoot.toNode(tab2.node, false);

			String btnText = QuiltLoaderText.translate("button.continue_to", getGameProvider().getGameName()).toString();
			tree.addButton(btnText, QuiltBasicButtonAction.CONTINUE);

			// TODO: Look into writing a report!

			try {
				QuiltGuiEntry.open(tree, null, true);
			} catch (Exception e) {
				e.printStackTrace();
			}

			return result;
		} catch (QuiltReportedError reported) {
			try {
				crashReportFile = reported.report.writeInDirectory(gameDir);
			} catch (CrashReportSaveFailed e) {
				fullCrashText = e.fullReportText;
			}
		}

		if ((provider != null && !provider.canOpenErrorGui()) || GraphicsEnvironment.isHeadless()) {
			if (crashReportFile != null) {
				System.err.println("Game crashed! Saved the crash report to " + crashReportFile);
			}
			if (fullCrashText != null) {
				System.err.println("Game crashed, and also failed to save the crash report!");
				System.err.println(fullCrashText);
			}
			System.exit(1);
			throw new Error("System.exit(1) failed!");
		}

		String msg = "crash.during_setup." + provider.getGameId();
		QuiltJsonGui tree = new QuiltJsonGui("Quilt Loader " + QuiltLoaderImpl.VERSION, QuiltLoaderText.translate(msg).toString());
		plugins.guiManager.putIcons(tree);
		tree.messagesTabName = QuiltLoaderText.translate("tab.messages").toString();

		if (fullCrashText != null) {
			QuiltPluginErrorImpl error = new QuiltPluginErrorImpl("quilt_loader", QuiltLoaderText.translate("error.failed_to_save_crash_report"));
			error.setIcon(GuiManagerImpl.ICON_LEVEL_ERROR);
			error.appendDescription(QuiltLoaderText.translate("error.failed_to_save_crash_report.desc"));
			error.appendAdditionalInformation(QuiltLoaderText.translate("error.failed_to_save_crash_report.info"));
			error.addCopyTextToClipboardButton(QuiltLoaderText.translate("button.copy_crash_report"), fullCrashText);
			tree.messages.add(error.toGuiMessage(tree));
		}

		int number = 1;
		List<QuiltPluginErrorImpl> pluginErrors = plugins.getErrors();
		for (QuiltPluginErrorImpl error : pluginErrors) {
			if (number > 200) {
				error = new QuiltPluginErrorImpl(MOD_ID, QuiltLoaderText.translate("error.too_many_errors"));
				error.appendDescription(QuiltLoaderText.translate("error.too_many_errors.desc", pluginErrors.size() - 200));
				tree.messages.add(0, error.toGuiMessage(tree));
				break;
			}
			tree.messages.add(error.toGuiMessage(tree));
			number++;
		}

		// TODO: Move tab creation to the plugin manager
		// so that the plugin manager can have tabs of both the file list
		// AND mod list!
		QuiltJsonGui.QuiltJsonGuiTreeTab tab = tree.addTab("Files");
		plugins.guiFileRoot.text(QuiltLoaderText.translate("tab.file_list"));
		plugins.guiFileRoot.toNode(tab.node, false);

		QuiltJsonGui.QuiltJsonGuiTreeTab tab2 = tree.addTab("Mods");
		plugins.guiModsRoot.text(QuiltLoaderText.translate("tab.mod_list"));
		plugins.guiModsRoot.toNode(tab2.node, false);

		if (crashReportFile != null) {
			tree.addButton(QuiltLoaderText.translate("button.open_crash_report").toString(), "text_file", QuiltBasicButtonAction.OPEN_FILE)//
				.arg("file", crashReportFile.toString());
			tree.addButton(QuiltLoaderText.translate("button.copy_crash_report").toString(), QuiltBasicButtonAction.PASTE_CLIPBOARD_FILE)//
				.arg("file", crashReportFile.toString());
		}

		tree.addButton(QuiltLoaderText.translate("Open Mods Folder").toString(), "folder", QuiltBasicButtonAction.VIEW_FOLDER)
			.arg("folder", getModsDir().toString());

		try {
			QuiltGuiEntry.open(tree, null, true);
			System.exit(1);
			throw new Error("System.exit(1) Failed!");
		} catch (Exception e) {
			throw new Error(e);
		}
	}

	public String createModTable() {
		StringBuilder sb = new StringBuilder();
		appendModTable(line -> {
			sb.append(line);
			sb.append("\n");
		});
		return sb.toString();
	}

	/** Appends each line of {@link #createModTable()} to the given consumer. */
	public void appendModTable(Consumer<String> to) {

		// Columns:
		// - Index
		// - Name
		// - ID
		// - version
		// - loader plugin
		// - source path(s)

		int maxNameLength = "Mod".length();
		int maxIdLength = "ID".length();
		int maxVersionLength = "Version".length();
		int maxPluginLength = "Plugin".length();
		List<Integer> maxSourcePathLengths = new ArrayList<>();

		for (ModContainerExt mod : mods) {
			maxNameLength = Math.max(maxNameLength, mod.metadata().name().length());
			maxIdLength = Math.max(maxIdLength, mod.metadata().id().length());
			maxVersionLength = Math.max(maxVersionLength, mod.metadata().version().toString().length());
			maxPluginLength = Math.max(maxPluginLength, mod.pluginId().length());

			for (List<Path> paths : mod.getSourcePaths()) {
				for (int i = 0; i < paths.size(); i++) {
					Path path = paths.get(i);
					String pathStr = path.startsWith(gameDir) ? "<game>/" + gameDir.relativize(path).toString() : path.toString();
					if (maxSourcePathLengths.size() <= i) {
						int old = (i == 0 ? "File(s)" : "Sub-Files").length();
						maxSourcePathLengths.add(Math.max(old, pathStr.length() + 1));
					} else {
						Integer old = maxSourcePathLengths.get(i);
						maxSourcePathLengths.set(i, Math.max(old, pathStr.length() + 1));
					}
				}
			}
		}

		maxIdLength++;
		maxVersionLength++;
		maxPluginLength++;

		StringBuilder sbTab = new StringBuilder();
		StringBuilder sbSep = new StringBuilder();

		// Table header
		sbTab.append("| Index | Mod ");
		sbSep.append("|------:|-----");
		for (int i = "Mod".length(); i < maxNameLength; i++) {
			sbTab.append(" ");
			sbSep.append("-");
		}
		sbTab.append("| ID ");
		sbSep.append("|----");
		for (int i = "ID".length(); i < maxIdLength; i++) {
			sbTab.append(" ");
			sbSep.append("-");
		}
		sbTab.append("| Version ");
		sbSep.append("|---------");
		for (int i = "Version".length(); i < maxVersionLength; i++) {
			sbTab.append(" ");
			sbSep.append("-");
		}
		sbTab.append("| Plugin ");
		sbSep.append("|--------");
		for (int i = "Plugin".length(); i < maxPluginLength; i++) {
			sbTab.append(" ");
			sbSep.append("-");
		}
		sbTab.append("|");
		sbSep.append("|");

		String start = "File(s)";

		for (int len : maxSourcePathLengths) {
			sbTab.append(" ").append(start);
			for (int i = start.length(); i <= len; i++) {
				sbTab.append(" ");
			}
			for (int i = -1; i <= len; i++) {
				sbSep.append("-");
			}
			sbTab.append("|");
			sbSep.append("|");
			start = "Sub-Files";
		}

		to.accept(sbTab.toString());
		sbTab.setLength(0);
		to.accept(sbSep.toString());

		for (ModContainerExt mod : mods.stream().sorted(Comparator.comparing(i -> i.metadata().name())).collect(Collectors.toList())) {
			// - Index
			// - Name
			// - ID
			// - version
			// - loader plugin
			// - source path(s)
			sbTab.append("| ");
			String index = Integer.toString(mods.indexOf(mod));
			for (int i = index.length(); i < "Index".length(); i++) {
				sbTab.append(" ");
			}
			sbTab.append(index).append(" | ").append(mod.metadata().name());
			for (int i = mod.metadata().name().length(); i < maxNameLength; i++) {
				sbTab.append(" ");
			}
			sbTab.append(" | ").append(mod.metadata().id());
			for (int i = mod.metadata().id().length(); i < maxIdLength; i++) {
				sbTab.append(" ");
			}
			sbTab.append(" | ").append(mod.metadata().version());
			for (int i = mod.metadata().version().toString().length(); i < maxVersionLength; i++) {
				sbTab.append(" ");
			}
			sbTab.append(" | ").append(mod.pluginId());
			for (int i = mod.pluginId().length(); i < maxPluginLength; i++) {
				sbTab.append(" ");
			}

			for (int pathsIndex = 0; pathsIndex < mod.getSourcePaths().size(); pathsIndex++) {
				List<Path> paths = mod.getSourcePaths().get(pathsIndex);

				if (pathsIndex != 0) {
					sbTab.append("\n| ");
					for (int i = 0; i < "Index".length(); i++) {
						sbTab.append(" ");
					}
					sbTab.append(" | ");
					for (int i = 0; i < maxIdLength; i++) {
						sbTab.append(" ");
					}
					sbTab.append(" | ");
					for (int i = 0; i < maxVersionLength; i++) {
						sbTab.append(" ");
					}
					sbTab.append(" | ");
					for (int i = 0; i < maxPluginLength; i++) {
						sbTab.append(" ");
					}
				}

				for (int pathIndex = 0; pathIndex < maxSourcePathLengths.size(); pathIndex++) {
					sbTab.append(" | ");
					final String pathStr;
					if (pathIndex < paths.size()) {
						Path path = paths.get(pathIndex);
						pathStr = path.startsWith(gameDir) ? "<game>/" + gameDir.relativize(path) : path.toString();
					} else {
						pathStr = "";
					}
					sbTab.append(pathStr);
					for (int i = pathStr.length(); i < maxSourcePathLengths.get(pathIndex); i++) {
						sbTab.append(" ");
					}
				}
				sbTab.append(" |");
			}
			to.accept(sbTab.toString());
			sbTab.setLength(0);
		}

		to.accept(sbSep.toString());
	}

	private static void performMixinReordering(List<ModLoadOption> modList) {

		// Keep Mixin 0.9.2 compatible mods first in the load order, temporary fix for https://github.com/FabricMC/Mixin/issues/89
		List<ModLoadOption> newMixinCompatMods = new ArrayList<>();

		for (Iterator<ModLoadOption> it = modList.iterator(); it.hasNext();) {
			ModLoadOption mod = it.next();
			boolean isFabric = mod instanceof FabricModOption;
			if (QuiltMixinBootstrap.MixinConfigDecorator.getMixinCompat(isFabric, mod.metadata()) != FabricUtil.COMPATIBILITY_0_9_2) {
				it.remove();
				newMixinCompatMods.add(mod);
			}
		}

		modList.addAll(newMixinCompatMods);
	}

	private static void performLoadLateReordering(List<ModLoadOption> modList) {
		String modsToLoadLate = System.getProperty(SystemProperties.DEBUG_LOAD_LATE);

		if (modsToLoadLate != null) {

			List<ModLoadOption> lateMods = new ArrayList<>();

			for (String modId : modsToLoadLate.split(",")) {
				for (Iterator<ModLoadOption> it = modList.iterator(); it.hasNext(); ) {
					ModLoadOption mod = it.next();

					if (mod.id().equals(modId)) {
						it.remove();
						lateMods.add(mod);
						break;
					}
				}
			}

			modList.addAll(lateMods);
		}
	}

	protected void finishModLoading() {
		// add mods to classpath
		// TODO: This can probably be made safer, but that's a long-term goal
		for (ModContainerExt mod : mods) {
			if (!mod.metadata().id().equals(MOD_ID) && mod.getSourceType() != BasicSourceType.BUILTIN) {
				File jarFile = copiedToJarMods.get(mod.metadata().id());
				if (jarFile == null) {
					URL origin = null;//mod.getSourcePaths();
					QuiltLauncherBase.getLauncher().addToClassPath(mod.rootPath(), mod, origin);
				} else {
					QuiltLauncherBase.getLauncher().addToClassPath(jarFile.toPath(), mod, null);
				}
			}
		}

		if (isDevelopmentEnvironment()) {
			// Many development environments will provide classes and resources as separate directories to the classpath.
			// As such, we're adding them to the classpath here and now.
			// To avoid tripping loader-side checks, we also don't add URLs already in modsList.
			// TODO: Perhaps a better solution would be to add the Sources of all parsed entrypoints. But this will do, for now.

			Set<Path> knownModPaths = new HashSet<>();

			for (ModContainerExt mod : mods) {
				if (mod.rootPath() instanceof QuiltJoinedPath) {
					QuiltJoinedPath joined = (QuiltJoinedPath) mod.rootPath();
					for (int i = 0; i < joined.getFileSystem().getBackingPathCount(); i++) {
						knownModPaths.add(joined.getFileSystem().getBackingPath(i, joined));
					}
				} else {
					knownModPaths.add(mod.rootPath());
				}
			}

			// suppress fabric loader explicitly in case its fabric.mod.json is in a different folder from the classes
			Path loaderPath = ClasspathModCandidateFinder.getLoaderPath();
			if (loaderPath != null) knownModPaths.add(loaderPath.toAbsolutePath().normalize());

			for (String pathName : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
				if (pathName.isEmpty() || pathName.endsWith("*")) continue;

				Path path = Paths.get(pathName).toAbsolutePath().normalize();

				if (Files.isDirectory(path) && knownModPaths.add(path)) {
					QuiltLauncherBase.getLauncher().addToClassPath(path);
				}
			}
		}

		postprocessModMetadata();
		setupLanguageAdapters();
		setupMods();
	}

	public boolean hasEntrypoints(String key) {
		return entrypointStorage.hasEntrypoints(key);
	}

	public <T> List<T> getEntrypoints(String key, Class<T> type) {
		return entrypointStorage.getEntrypoints(key, type);
	}

	public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
		return entrypointStorage.getEntrypointContainers(key, type);
	}

	public MappingResolver getMappingResolver() {
		if (mappingResolver == null) {
			mappingResolver = new QuiltMappingResolver(
				QuiltLauncherBase.getLauncher().getMappingConfiguration()::getMappings,
				QuiltLauncherBase.getLauncher().getTargetNamespace()
			);
		}

		return mappingResolver;
	}

	public Optional<org.quiltmc.loader.api.ModContainer> getModContainer(String id) {
		return Optional.ofNullable(modMap.get(id));
	}

	public Optional<org.quiltmc.loader.api.ModContainer> getModContainer(Class<?> clazz) {
		ProtectionDomain pd = clazz.getProtectionDomain();
		if (pd != null) {
			CodeSource codeSource = pd.getCodeSource();
			if (codeSource instanceof QuiltCodeSource) {
				return ((QuiltCodeSource) codeSource).getQuiltMod();
			}
		}
		return Optional.empty();
	}

	// TODO: add to QuiltLoader api
	public ObjectShare getObjectShare() {
		return objectShare;
	}

	public Collection<org.quiltmc.loader.api.ModContainer> getAllMods() {
		return Collections.unmodifiableList(mods);
	}

	public Collection<ModContainerExt> getAllModsExt() {
		return Collections.unmodifiableList(mods);
	}

	public boolean isModLoaded(String id) {
		return modMap.containsKey(id);
	}

	public boolean isDevelopmentEnvironment() {
		QuiltLauncher launcher = QuiltLauncherBase.getLauncher();
		if (launcher == null) {
			// Most likely a test
			return true;
		}
		return launcher.isDevelopment();
	}

	protected void addMod(ModContainerExt mod) throws ModResolutionException {
		ModMetadataExt meta = mod.metadata();

		if (modMap.containsKey(meta.id())) {
			throw new ModSolvingError("Duplicate mod ID: " + meta.id() + "!"/* + " (" + modMap.get(meta.id()).getOriginPath().toFile() + ", " + origin + ")"*/);
		}

		mods.add(mod);
		modMap.put(meta.id(), mod);

		for (ProvidedMod provided : meta.provides()) {
			if (modMap.containsKey(provided.id())) {
				throw new ModSolvingError("Duplicate provided alias: " + provided + "!" /*+ " (" + modMap.get(meta.id()).getOriginPath().toFile() + ", " + origin + ")"*/);
			}

			modMap.put(provided.id(), mod);
		}
	}

	protected void postprocessModMetadata() {
		// do nothing for now; most warnings have been moved to V1ModMetadataParser
	}

	private void setupLanguageAdapters() {
		adapterMap.put("default", DefaultLanguageAdapter.INSTANCE);

		for (ModContainerExt mod : mods) {
			// add language adapters
			for (Map.Entry<String, String> laEntry : mod.metadata().languageAdapters().entrySet()) {
				if (adapterMap.containsKey(laEntry.getKey())) {
					throw new RuntimeException("Duplicate language adapter key: " + laEntry.getKey() + "! (" + laEntry.getValue() + ", " + adapterMap.get(laEntry.getKey()).getClass().getName() + ")");
				}

				try {
					adapterMap.put(laEntry.getKey(), new ModLanguageAdapter(mod, laEntry.getKey(), laEntry.getValue()));
				} catch (Exception e) {
					throw new RuntimeException("Failed to instantiate language adapter: " + laEntry.getKey(), e);
				}
			}
		}
	}

	private void setupMods() {
		for (ModContainerExt mod : mods) {
			try {
				if (mod.getSourceType() == BasicSourceType.NORMAL_FABRIC) {
					FabricLoaderModMetadata fabricMeta = ((InternalModMetadata) mod.metadata()).asFabricModMetadata(mod);
					for (String in : fabricMeta.getOldInitializers()) {
						String adapter = fabricMeta.getOldStyleLanguageAdapter();
						entrypointStorage.addDeprecated(mod, adapter, in);
					}
				}

				for (Map.Entry<String, Collection<AdapterLoadableClassEntry>> entry : mod.metadata().getEntrypoints().entrySet()) {
					for (AdapterLoadableClassEntry e : entry.getValue()) {
						entrypointStorage.add(mod, entry.getKey(), e, adapterMap);
					}
				}
			} catch (Exception e) {
				throw new RuntimeException(String.format("Failed to setup mod %s (%s)", mod.metadata().name(), mod.rootPath()), e);
			}
		}
	}

	public void loadAccessWideners() {
		AccessWidenerReader accessWidenerReader = new AccessWidenerReader(accessWidener);

		for (ModContainerExt mod : mods) {
			for (String accessWidener : mod.metadata().accessWideners()) {

				Path path = mod.getPath(accessWidener);

				if (!Files.isRegularFile(path)) {
					throw new RuntimeException("Failed to find accessWidener file from mod " + mod.metadata().id() + " '" + accessWidener + "'");
				}

				try (BufferedReader reader = Files.newBufferedReader(path)) {
					accessWidenerReader.read(reader, getMappingResolver().getCurrentRuntimeNamespace());
				} catch (Exception e) {
					throw new RuntimeException("Failed to read accessWidener file from mod " + mod.metadata().id(), e);
				}
			}
		}
	}

	public void prepareModInit(Path newRunDir, Object gameInstance) {
		if (!frozen) {
			throw new RuntimeException("Cannot instantiate mods when not frozen!");
		}

		if (gameInstance != null && QuiltLauncherBase.getLauncher() instanceof Knot) {
			ClassLoader gameClassLoader = gameInstance.getClass().getClassLoader();
			ClassLoader targetClassLoader = QuiltLauncherBase.getLauncher().getTargetClassLoader();
			boolean matchesKnot = (gameClassLoader == targetClassLoader);
			boolean containsKnot = false;

			if (matchesKnot) {
				containsKnot = true;
			} else {
				gameClassLoader = gameClassLoader.getParent();

				while (gameClassLoader != null && gameClassLoader.getParent() != gameClassLoader) {
					if (gameClassLoader == targetClassLoader) {
						containsKnot = true;
					}

					gameClassLoader = gameClassLoader.getParent();
				}
			}

			if (!matchesKnot) {
				if (containsKnot) {
					Log.info(LogCategory.KNOT, "Environment: Target class loader is parent of game class loader.");
				} else {
					Log.warn(LogCategory.KNOT, "\n\n* CLASS LOADER MISMATCH! THIS IS VERY BAD AND WILL PROBABLY CAUSE WEIRD ISSUES! *\n"
							+ " - Expected game class loader: %s\n"
							+ " - Actual game class loader: %s\n"
							+ "Could not find the expected class loader in game class loader parents!\n",
							QuiltLauncherBase.getLauncher().getTargetClassLoader(), gameClassLoader);
				}
			}
		}

		this.gameInstance = gameInstance;

		if (gameDir != null) {
			try {
				if (!gameDir.toRealPath().equals(newRunDir.toRealPath())) {
					Log.warn(LogCategory.GENERAL, "Inconsistent game execution directories: engine says %s, while initializer says %s...",
							newRunDir.toRealPath(), gameDir.toRealPath());
					setGameDir(newRunDir);
				}
			} catch (IOException e) {
				Log.warn(LogCategory.GENERAL, "Exception while checking game execution directory consistency!", e);
			}
		} else {
			setGameDir(newRunDir);
		}
	}

	public void invokePreLaunch() {
		try {
			EntrypointUtils.invoke("pre_launch", org.quiltmc.loader.api.entrypoint.PreLaunchEntrypoint.class, org.quiltmc.loader.api.entrypoint.PreLaunchEntrypoint::onPreLaunch);
			EntrypointUtils.invoke("preLaunch", net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint.class, net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint::onPreLaunch);
		} catch (RuntimeException e) {
			throw new FormattedException("A mod crashed on startup!", e);
		}

		for (LanguageAdapter adapter : adapterMap.values()) {
			if (adapter instanceof ModLanguageAdapter) {
				((ModLanguageAdapter) adapter).init();
			}
		}
	}

	public AccessWidener getAccessWidener() {
		return accessWidener;
	}

	/**
	 * Sets the game instance. This is only used in 20w22a+ by the dedicated server and should not be called by anything else.
	 */
	@Deprecated
	public void setGameInstance(Object gameInstance) {
		if (this.getEnvironmentType() != EnvType.SERVER) {
			throw new UnsupportedOperationException("Cannot set game instance on a client!");
		}

		if (this.gameInstance != null) {
			throw new UnsupportedOperationException("Cannot overwrite current game instance!");
		}

		this.gameInstance = gameInstance;
	}

	public String[] getLaunchArguments(boolean sanitize) {
		return getGameProvider().getLaunchArguments(sanitize);
	}

	/**
	 * Provides singleton for static init assignment regardless of load order.
	 */
	public static class InitHelper {
		private static QuiltLoaderImpl instance;

		public static QuiltLoaderImpl get() {
			if (instance == null) instance = new QuiltLoaderImpl();

			return instance;
		}
	}
}
