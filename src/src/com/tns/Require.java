package com.tns;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

public class Require
{
	private static String RootPackageDir;
	private static String ApplicationFilesPath;
	private static String ModulesFilesPath;
	private static String NativeScriptModulesFilesPath;
	private static boolean initialized = false;
	private static final String ModuleContent = "(function(){\n var module = {}; module.exports = arguments[0];" + 
			"var exports = module.exports; var __dirname = \"%s\"; var __filename = \"%s\";" + 
			"function require(moduleName){ return global.require(moduleName, __filename); }" + 
			"module.filename = __filename; this.__extends = global.__extends; \n %s \n return module.exports; \n})";
	private static final String AssetFile = "ASSET:";
	private static final String FileNotFound = "";
	private static Context AppContext;

	public static void init(Context context)
	{
		if (initialized)
		{
			return;
		}

		AppContext = context;
		RootPackageDir = context.getApplicationInfo().dataDir;

		ApplicationFilesPath = context.getApplicationContext().getFilesDir().getPath();
		ModulesFilesPath = "/app/";
		NativeScriptModulesFilesPath = "/tns_modules/";

		initialized = true;
	}

	public static String getApplicationFilesPath()
	{
		return ApplicationFilesPath;
	}

	public static String getAppContent(String appFileName)
	{
		String bootstrapFile = getModulePath(appFileName, "");
		return getModuleContent(bootstrapFile);
	}

	public static String getModuleContent(String modulePath)
	{
		if (Platform.IsLogEnabled)
		{
			Log.d(Platform.DEFAULT_LOG_TAG, "Loading module " + modulePath);
		}
		
		String content;
		String parent = "";
		if(modulePath.startsWith(AssetFile))
		{
			content = FileSystem.readAssetFile(AppContext.getAssets(), modulePath.replace(AssetFile, ""));
		}
		else
		{
			content = FileSystem.readFile(modulePath);
			parent = new File(modulePath).getParent();
		}
		
		// IMPORTANT: Update MODULE_LINES_OFFSET in NativeScript.h if you change the number of new lines
		// that exists before the moduleFileContent for correct error reporting.
		// We are inserting local require function in the scope of the
		// module to pass the __fileName (calling file) variable in the global.require request.
		return String.format(ModuleContent, parent, modulePath, content);
	}

	public static String getModulePath(String moduleName, String callingModuleName)
	{
		// This method is called my the NativeScriptRuntime.cpp RequireCallback method.
		// The currentModuleName is the fully-qualified path of the previously loaded module (if any)
		String currentDirectory = null;

		if (callingModuleName != null && !callingModuleName.isEmpty())
		{
			File currentModule = new File(callingModuleName);
			if (currentModule.exists())
			{
				String parentDirectory = currentModule.getParent();
				if (parentDirectory != null)
				{
					currentDirectory = parentDirectory + "/";
				}
			}
		}

		File file = findModuleFile(moduleName, currentDirectory);
		if(file == null)
		{
			return FileNotFound;
		}

		if (file.exists())
		{
			File projectRootDir = new File(RootPackageDir);
			if (isFileExternal(file, projectRootDir))
			{
				return "EXTERNAL_FILE_ERROR";
			}
			else
			{
				return file.getPath();
			}
		}
		else
		{
			return tryGetAssetFile(file);
		}
	}

	private static String tryGetAssetFile(File file)
	{
		AssetManager manager = AppContext.getAssets();
		String path = getAssetFileRelativePath(file.getPath());
		String parentPath = getAssetFileRelativePath(file.getParent());
		
		String pathToList = path;
		Boolean isJSFile = path.endsWith(".js");
		if(isJSFile)
		{
			pathToList = parentPath;
		}
		
		try
		{
			String[] assetsAtPath = manager.list(pathToList);
			
			String asset;
			String entryFile = FileNotFound;
			for(int i = 0; i < assetsAtPath.length; i++)
			{
				asset = assetsAtPath[i];
				
				// check for direct match or index.js
				if(path.endsWith(asset) || asset == "index.js")
				{
					entryFile = pathToList + "/" + asset;
					break;
				}
				
				// check for package.json
				if(asset == "package.json")
				{
					entryFile = path + "/" + getMainFileFromJSON(FileSystem.readAssetFile(manager, asset));
					break;
				}
			}
			
			if(entryFile != FileNotFound)
			{
				entryFile = AssetFile + entryFile;
			}
			
			return entryFile;
		}
		catch (IOException e)
		{
			e.printStackTrace();
			return FileNotFound;
		}
	}
	
	private static String getAssetFileRelativePath(String absolutePath)
	{
		return absolutePath.replace(ApplicationFilesPath + "/", "");
	}
	
	private static String getMainFileFromJSON(String json)
	{
		if(json == FileNotFound)
		{
			return FileNotFound;
		}
		
		try
		{
			JSONObject obj = new JSONObject(json);
			return obj.getString("main");
		}
		catch (JSONException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
			return FileNotFound;
		}
	}

	private static boolean isFileExternal(File source, File target)
	{
		File currentParentDir = source.getParentFile();

		while (currentParentDir != null)
		{
			if (currentParentDir.equals(target))
			{
				return false;
			}

			currentParentDir = currentParentDir.getParentFile();
		}

		return true;
	}

	private static File findModuleFile(String moduleName, String currentDirectory)
	{
		File directory = null;
		File jsFile = null;
		boolean isJSFile = moduleName.endsWith(".js");

		if (currentDirectory == null || currentDirectory.isEmpty())
		{
			currentDirectory = ApplicationFilesPath + ModulesFilesPath;
		}

		if (moduleName.startsWith("/"))
		{
			// absolute path
			directory = new File(moduleName);
			jsFile = isJSFile ? new File(moduleName) : new File(moduleName + ".js");
		}
		else if (moduleName.startsWith("./") || moduleName.startsWith("../"))
		{
			// same or up directory
			String resolvedPath = FileSystem.resolveRelativePath(moduleName, currentDirectory);
			directory = new File(resolvedPath);
			jsFile = isJSFile ? new File(directory.getPath()) : new File(directory.getPath() + ".js");
		}
		else
		{
			// search for tns_module
			directory = new File(ApplicationFilesPath + NativeScriptModulesFilesPath, moduleName);
			jsFile = isJSFile ? new File(directory.getPath()) : new File(directory.getPath() + ".js");
		}

		if (!jsFile.exists() && directory.exists() && directory.isDirectory())
		{
			// we are pointing to a directory, search for package.json or
			// index.js
			File packageFile = new File(directory.getPath() + "/package.json");
			if (packageFile.exists())
			{
				try
				{
					JSONObject object = FileSystem.readJSONFile(packageFile);
					if (object != null)
					{
						String mainFile = object.getString("main");
						jsFile = new File(directory.getPath(), mainFile);
					}
				}
				catch (IOException e)
				{
					// json read failed
					jsFile = null;
				}
				catch (JSONException e)
				{
					jsFile = null;
				}
			}
			else
			{
				// search for index.js
				jsFile = new File(directory.getPath() + "/index.js");
			}
		}

		return jsFile;
	}
}
