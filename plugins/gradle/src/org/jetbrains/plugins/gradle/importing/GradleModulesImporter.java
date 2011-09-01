package org.jetbrains.plugins.gradle.importing;

import com.intellij.openapi.application.*;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.StdModuleTypes;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.impl.libraries.ProjectLibraryTable;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.util.Alarm;
import com.intellij.util.containers.hash.HashMap;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.importing.model.*;
import org.jetbrains.plugins.gradle.remote.GradleApiFacadeManager;
import org.jetbrains.plugins.gradle.remote.GradleProjectResolver;
import org.jetbrains.plugins.gradle.util.GradleBundle;
import org.jetbrains.plugins.gradle.util.GradleLog;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Encapsulates functionality of creating IntelliJ IDEA modules on the basis of {@link GradleModule gradle modules}.
 * <p/>
 * Not thread-safe.
 * 
 * @author Denis Zhdanov
 * @since 8/26/11 10:01 AM
 */
public class GradleModulesImporter {
  
  /**
   * We can't modify project modules (add/remove) until it's initialised, so, we delay that activity. Current constant
   * holds number of milliseconds to wait between 'after project initialisation' processing attempts.
   */
  private static final int PROJECT_INITIALISATION_DELAY_MS = (int)TimeUnit.SECONDS.toMillis(1);
  
  private final Alarm myAlarm = new Alarm(Alarm.ThreadToUse.SHARED_THREAD);

  /**
   * Entry point for the whole 'import modules' procedure.
   * 
   * @param modules            module info containers received from the gradle api
   * @param project            project that should host the modules
   * @param model              modules model
   * @param gradleProjectPath  file system path to the gradle project file being imported
   * @return                   mappings between the given gradle modules and newly created intellij modules
   */
  @NotNull
  public Map<GradleModule, Module> importModules(@NotNull final Iterable<GradleModule> modules, @Nullable final Project project, 
                                                 @Nullable final ModifiableModuleModel model, @NotNull String gradleProjectPath)
  {
    if (project == null) {
      return Collections.emptyMap();
    }
    removeExistingModulesSettings(modules);
    if (!project.isInitialized()) {
      myAlarm.addRequest(new ImportModulesTask(project, modules, gradleProjectPath), PROJECT_INITIALISATION_DELAY_MS);
      return Collections.emptyMap();
    } 
    if (model == null) {
      return Collections.emptyMap();
    }
    return importModules(modules, model, project, gradleProjectPath);
  }

  private static void removeExistingModulesSettings(@NotNull Iterable<GradleModule> modules) {
    for (GradleModule module : modules) {
      // Remove existing '*.iml' file if necessary.
      final String moduleFilePath = module.getModuleFilePath();
      File file = new File(moduleFilePath);
      if (file.isFile()) {
        boolean success = file.delete();
        if (!success) {
          GradleLog.LOG.warn("Can't remove existing module file at '" + moduleFilePath + "'");
        }
      }
    }
  }
  
  public Map<GradleModule, Module> importModules(@NotNull final Iterable<GradleModule> modules, 
                                                 @NotNull final ModifiableModuleModel model,
                                                 @NotNull final Project intellijProject,
                                                 @NotNull final String gradleProjectPath)
  {
    final Map<GradleModule, Module> result = new HashMap<GradleModule, Module>();
    UIUtil.invokeAndWaitIfNeeded(new Runnable() {
      @Override
      public void run() {
        Application application = ApplicationManager.getApplication();
        AccessToken writeLock = application.acquireWriteActionLock(getClass());
        try {
          try {
            Map<GradleModule, Module> moduleMappings = doImportModules(modules, model);
            result.putAll(moduleMappings);
            myAlarm.cancelAllRequests();
            myAlarm.addRequest(
              new SetupExternalLibrariesTask(moduleMappings, gradleProjectPath, intellijProject),
              PROJECT_INITIALISATION_DELAY_MS
            );
          }
          finally {
            model.commit();
          }
        }
        finally {
          writeLock.finish();
        }
      }
    });
    return result;
  }

  /**
   * Actual implementation of {@link #importModules(Iterable, Project, ModifiableModuleModel, String)}. Insists on all arguments to
   * be ready to use.
   *  
   * @param modules  modules to import
   * @param model    modules model
   * @return         mappings between the given gradle modules and corresponding intellij modules
   */
  @NotNull
  @SuppressWarnings("MethodMayBeStatic")
  private Map<GradleModule, Module> doImportModules(@NotNull Iterable<GradleModule> modules, @NotNull ModifiableModuleModel model) {
    Map<GradleModule, Module> result = new HashMap<GradleModule, Module>();
    for (GradleModule moduleToImport : modules) {
      Module createdModule = createModule(moduleToImport, model);
      result.put(moduleToImport, createdModule);
    }
    for (GradleModule moduleToImport : modules) {
      configureModule(moduleToImport, result);
    }
    return result;
  }

  /**
   * We need to create module objects for all modules at first and then configure them. That is necessary for setting up
   * module dependencies.
   * 
   * @param module  gradle module to import
   * @param model   module model
   * @return        newly created IJ module
   */
  @NotNull
  private static Module createModule(@NotNull GradleModule module, @NotNull ModifiableModuleModel model) {
    Application application = ApplicationManager.getApplication();
    application.assertWriteAccessAllowed();
    final String moduleFilePath = module.getModuleFilePath();
    return model.newModule(moduleFilePath, StdModuleTypes.JAVA);
  }

  /**
   * Applies module settings received from the gradle api (encapsulate at the given {@link GradleModule} object) to the
   * target intellij module (retrieved from the given module mappings).
   * 
   * @param module   target gradle module which corresponding intellij module should be configured
   * @param modules  gradle module to intellij modules mappings. Is assumed to have a value for the given gradle modules used as a key
   */
  private static void configureModule(@NotNull GradleModule module, @NotNull Map<GradleModule, Module> modules) {
    Application application = ApplicationManager.getApplication();
    application.assertWriteAccessAllowed();
    
    ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(modules.get(module));
    ModifiableRootModel rootModel = moduleRootManager.getModifiableModel();
    try {
      configureModule(module, rootModel, modules);
    }
    finally {
      rootModel.commit();
    }
  }

  /**
   * Contains actual logic of {@link #configureModule(GradleModule, Map)}.
   * 
   * @param module   target module settings holder
   * @param model    intellij module setting manager
   * @param modules  modules mappings
   */
  private static void configureModule(@NotNull GradleModule module, @NotNull final ModifiableRootModel model,
                                      @NotNull final Map<GradleModule, Module> modules)
  {
    // Ensure that dependencies are clear.
    for (OrderEntry orderEntry : model.getOrderEntries()) {
      model.removeOrderEntry(orderEntry);
    }
    
    // Configure SDK.
    model.inheritSdk();

    // Compile output.
    CompilerModuleExtension compilerExtension = model.getModuleExtension(CompilerModuleExtension.class);
    compilerExtension.inheritCompilerOutputPath(module.isInheritProjectCompileOutputPath());
    if (!module.isInheritProjectCompileOutputPath()) {
      compilerExtension.setCompilerOutputPath(module.getCompileOutputPath(SourceType.SOURCE));
      compilerExtension.setCompilerOutputPathForTests(module.getCompileOutputPath(SourceType.TEST));
    }
    
    // Content roots.
    for (GradleContentRoot contentRoot : module.getContentRoots()) {
      ContentEntry contentEntry = model.addContentEntry(toVfsUrl(contentRoot.getRootPath()));
      for (String path : contentRoot.getPaths(SourceType.SOURCE)) {
        contentEntry.addSourceFolder(toVfsUrl(path), false);
      }
      for (String path : contentRoot.getPaths(SourceType.TEST)) {
        contentEntry.addSourceFolder(toVfsUrl(path), true);
      }
      for (String path : contentRoot.getPaths(SourceType.EXCLUDED)) {
        contentEntry.addExcludeFolder(toVfsUrl(path));
      }
    }
    
    // Module dependencies.
    for (GradleDependency dependency : module.getDependencies()) {
      dependency.invite(new GradleEntityVisitorAdapter() {
        @Override
        public void visit(@NotNull GradleModuleDependency dependency) {
          ModuleOrderEntry orderEntry = model.addModuleOrderEntry(modules.get(dependency.getModule()));
          orderEntry.setExported(dependency.isExported());
          orderEntry.setScope(dependency.getScope());
        }
      });
    }
  }

  /**
   * Resolves (downloads if necessary) external libraries necessary for the gradle project located at the given path and configures
   * them for the corresponding intellij project.
   * <p/>
   * <b>Note:</b> is assumed to be executed under write action.
   * 
   * @param moduleMappings     gradle-intellij module mappings
   * @param intellijProject    intellij project for the target gradle project
   * @param gradleProjectPath  file system path to the target gradle project
   */
  private static void setupLibraries(@NotNull final Map<GradleModule, Module> moduleMappings,
                                     @NotNull final Project intellijProject,
                                     @NotNull final String gradleProjectPath)
  {
    final GradleApiFacadeManager manager = ServiceManager.getService(GradleApiFacadeManager.class);
    
    Runnable edtAction = new Runnable() {
      @Override
      public void run() {
        final Ref<GradleProject> gradleProjectRef = new Ref<GradleProject>();
        //ProgressManager.getInstance().runProcessWithProgressSynchronously(new Runnable() {
        ProgressManager.getInstance().run(
          new Task.Backgroundable(intellijProject, GradleBundle.message("gradle.library.resolve.progress.text"), false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
              indicator.setIndeterminate(true);
              try {
                GradleProjectResolver resolver = manager.getFacade().getResolver();
                gradleProjectRef.set(resolver.resolveProjectInfo(gradleProjectPath, true));
              }
              catch (Exception e) {
                GradleLog.LOG.warn("Can't resolve external dependencies of the target gradle project (" + gradleProjectPath + ")", e);
              } 
            }
        });

        final GradleProject gradleProject = gradleProjectRef.get();
        if (gradleProject == null) {
          return;
        }
        
        Application application = ApplicationManager.getApplication();
        AccessToken writeLock = application.acquireWriteActionLock(getClass());
        try {
          doSetupLibraries(moduleMappings, gradleProject, intellijProject);
        }
        finally {
          writeLock.finish();
        }
      }
    };
    UIUtil.invokeLaterIfNeeded(edtAction);
  }

  private static void doSetupLibraries(@NotNull Map<GradleModule, Module> moduleMappings,
                                       @NotNull GradleProject gradleProject,
                                       @NotNull Project intellijProject)
  {
    Application application = ApplicationManager.getApplication();
    application.assertWriteAccessAllowed();
    
    Map<GradleLibrary, Library> libraryMappings = registerProjectLibraries(gradleProject, intellijProject);
    if (libraryMappings == null) {
      return;
    }

    configureModulesLibraryDependencies(moduleMappings, libraryMappings, gradleProject);
  }

  /**
   * Registers {@link GradleProject#getLibraries() libraries} of the given gradle project at the intellij project.
   * 
   * @param gradleProject    target gradle project being imported
   * @param intellijProject  intellij representation of the given gradle project
   * @return                 mapping between libraries of the given gradle and intellij projects
   */
  @Nullable
  private static Map<GradleLibrary, Library> registerProjectLibraries(GradleProject gradleProject, Project intellijProject) {
    LibraryTable projectLibraryTable = ProjectLibraryTable.getInstance(intellijProject);
    if (projectLibraryTable == null) {
      GradleLog.LOG.warn(
        "Can't resolve external dependencies of the target gradle project (" + intellijProject + "). Reason: project "
        + "library table is undefined"
      );
      return null;
    }
    Map<GradleLibrary, Library> libraryMappings = new HashMap<GradleLibrary, Library>();
    for (GradleLibrary gradleLibrary : gradleProject.getLibraries()) {
      Library intellijLibrary = projectLibraryTable.createLibrary(gradleLibrary.getName());
      libraryMappings.put(gradleLibrary, intellijLibrary);
      Library.ModifiableModel model = intellijLibrary.getModifiableModel();
      try {
        registerPath(gradleLibrary, model);
      }
      finally {
        model.commit();
      }
    }
    return libraryMappings;
  }

  private static void configureModulesLibraryDependencies(@NotNull Map<GradleModule, Module> moduleMappings,
                                                          @NotNull final Map<GradleLibrary, Library> libraryMappings,
                                                          @NotNull GradleProject gradleProject) {
    for (GradleModule gradleModule : gradleProject.getModules()) {
      Module intellijModule = moduleMappings.get(gradleModule);
      if (intellijModule == null) {
        GradleLog.LOG.warn(String.format(
          "Can't find intellij module for the gradle module '%s'. Registered mappings: %s", gradleModule, moduleMappings
        ));
        continue;
      }
      ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(intellijModule);
      final ModifiableRootModel moduleRootModel = moduleRootManager.getModifiableModel();
      GradleEntityVisitor visitor = new GradleEntityVisitorAdapter() {
        @Override
        public void visit(@NotNull GradleLibraryDependency dependency) {
          GradleLibrary gradleLibrary = dependency.getLibrary();
          Library intellijLibrary = libraryMappings.get(gradleLibrary);
          if (intellijLibrary == null) {
            GradleLog.LOG.warn(String.format(
              "Can't find registered intellij library for gradle library '%s'. Registered mappings: %s", gradleLibrary, libraryMappings
            ));
            return;
          }
          LibraryOrderEntry orderEntry = moduleRootModel.addLibraryEntry(intellijLibrary);
          orderEntry.setExported(dependency.isExported());
          orderEntry.setScope(dependency.getScope());
        }
      };
      try {
        for (GradleDependency dependency : gradleModule.getDependencies()) {
          dependency.invite(visitor);
        }
      }
      finally {
        moduleRootModel.commit();
      }
    }
  }

  private static void registerPath(@NotNull GradleLibrary gradleLibrary, @NotNull Library.ModifiableModel model) {
    for (LibraryPathType pathType : LibraryPathType.values()) {
      String path = gradleLibrary.getPath(pathType);
      if (path != null) {
        model.addRoot(toVfsUrl(path), pathType.getRootType());
      }
    }
  }
  
  private static String toVfsUrl(@NotNull String path) {
    return LocalFileSystem.PROTOCOL_PREFIX + path;
  }
  
  private class ImportModulesTask implements Runnable {
    
    private final Project myProject;
    private final Iterable<GradleModule> myModules;
    private final String myGradleProjectPath;

    private ImportModulesTask(@NotNull Project project, @NotNull Iterable<GradleModule> modules, @NotNull String gradleProjectPath) {
      myProject = project;
      myModules = modules;
      myGradleProjectPath = gradleProjectPath;
    }

    @Override
    public void run() {
      myAlarm.cancelAllRequests();
      if (!myProject.isInitialized()) {
        myAlarm.addRequest(new ImportModulesTask(myProject, myModules, myGradleProjectPath), PROJECT_INITIALISATION_DELAY_MS);
        return;
      } 
      
      final ModifiableModuleModel model = new ReadAction<ModifiableModuleModel>() {
        protected void run(Result<ModifiableModuleModel> result) throws Throwable {
          result.setResult(ModuleManager.getInstance(myProject).getModifiableModel());
        }
      }.execute().getResultObject();
      
      importModules(myModules, model, myProject, myGradleProjectPath);
    }
  }
  
  private static class SetupExternalLibrariesTask implements Runnable {

    private final Map<GradleModule, Module> myModules;
    private final String                    myGradleProjectPath;
    private final Project                   myIntellijProject;

    SetupExternalLibrariesTask(@NotNull Map<GradleModule, Module> modules, @NotNull String gradleProjectPath, Project intellijProject) {
      myModules = modules;
      myGradleProjectPath = gradleProjectPath;
      myIntellijProject = intellijProject;
    }

    @Override
    public void run() {
      setupLibraries(myModules, myIntellijProject, myGradleProjectPath); 
    }
  }
}
