package tel.panfilov.intellij.plugin.springboot;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ModuleBasedConfiguration;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.runners.JavaProgramPatcher;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import org.jdom.Element;
import org.jetbrains.idea.maven.dom.MavenDomUtil;
import org.jetbrains.idea.maven.dom.MavenPropertyResolver;
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel;
import org.jetbrains.idea.maven.model.MavenPlugin;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpringBootPatcher extends JavaProgramPatcher {

    public static final Pattern PROPERTY_PATTERN = Pattern.compile("\\$\\{(.+?)}");

    public static final Pattern ARG_LINE_PATTERN = Pattern.compile("@\\{(.+?)}");

    @Override
    public void patchJavaParameters(Executor executor, RunProfile runProfile, JavaParameters javaParameters) {
        if (!(runProfile instanceof ModuleBasedConfiguration)) {
            return;
        }
        Module module = ((ModuleBasedConfiguration) runProfile).getConfigurationModule().getModule();
        if (module == null) {
            return;
        }
        Project project = module.getProject();
        String mainClass = javaParameters.getMainClass();
        MavenProject mavenProject = MavenProjectsManager.getInstance(project).findProject(module);
        if (mavenProject == null) {
            return;
        }
        MavenPlugin plugin = mavenProject.findPlugin("org.springframework.boot", "spring-boot-maven-plugin");
        if (plugin == null) {
            return;
        }

        Element config = plugin.getExecutions().stream()
                .filter(e -> e.getGoals().contains("run"))
                .map(MavenPlugin.Execution::getConfigurationElement)
                .filter(Objects::nonNull)
                .filter(c -> {
                    Element mce = c.getChild("mainClass");
                    return mce != null && mainClass.equals(mce.getValue());
                })
                .findFirst()
                .orElse(null);

        if (config == null) {
            return;
        }

        MavenDomProjectModel domModel = MavenDomUtil.getMavenDomProjectModel(project, mavenProject.getFile());
        UnaryOperator<String> runtimeProperties = getDynamicConfigurationProperties(module, mavenProject, javaParameters);

        setupSystemPropertyVariables(javaParameters, config, domModel);
        setupWorkingDirectory(javaParameters, config, domModel);
        setupJvmArguments(javaParameters, config, domModel, runtimeProperties);

    }

    protected void setupSystemPropertyVariables(JavaParameters javaParameters, Element config, MavenDomProjectModel domModel) {
        Element systemPropertyVariables = config.getChild("systemPropertyVariables");
        if (systemPropertyVariables == null) {
            return;
        }

        for (Element element : systemPropertyVariables.getChildren()) {
            String propertyName = element.getName();
            if (javaParameters.getVMParametersList().hasProperty(propertyName)) {
                continue;
            }
            String value = resolvePluginProperties(element.getValue(), domModel);
            value = resolveVmProperties(javaParameters.getVMParametersList(), value);
            if (isResolved(value)) {
                javaParameters.getVMParametersList().addProperty(propertyName, value);
            }
        }
    }

    protected void setupWorkingDirectory(JavaParameters javaParameters, Element config, MavenDomProjectModel domModel) {
        Element wde = config.getChild("workingDirectory");
        String workingDirectory = wde != null ? wde.getTextTrim() : "${spring-boot.run.workingDirectory}";
        workingDirectory = resolvePluginProperties(workingDirectory, domModel);
        if (StringUtil.isNotEmpty(workingDirectory) && isResolved(workingDirectory)) {
            javaParameters.setWorkingDirectory(workingDirectory);
        }
    }

    protected void setupJvmArguments(JavaParameters javaParameters, Element config, MavenDomProjectModel domModel, UnaryOperator<String> runtimeProperties) {
        Element jae = config.getChild("jvmArguments");
        String jvmAguments = jae != null ? jae.getTextTrim() : "${spring-boot.run.jvmArguments}";
        jvmAguments = resolvePluginProperties(jvmAguments, domModel);
        if (StringUtil.isNotEmpty(jvmAguments) && isResolved(jvmAguments)) {
            jvmAguments = resolveRuntimeProperties(jvmAguments, runtimeProperties);
            javaParameters.getVMParametersList().addParametersString(jvmAguments);
        }
    }

    private static String resolveRuntimeProperties(String value, UnaryOperator<String> runtimeProperties) {
        Matcher matcher = ARG_LINE_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String replacement = runtimeProperties.apply(matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static UnaryOperator<String> getDynamicConfigurationProperties(Module module, MavenProject mavenProject, JavaParameters javaParameters) {
        MavenDomProjectModel domModel = MavenDomUtil.getMavenDomProjectModel(module.getProject(), mavenProject.getFile());
        if (domModel == null) {
            return s -> s;
        }
        Properties staticProperties = MavenPropertyResolver.collectPropertiesFromDOM(mavenProject, domModel);
        ParametersList vmParameters = javaParameters.getVMParametersList();
        return name -> {
            String vmPropertyValue = vmParameters.getPropertyValue(name);
            if (vmPropertyValue != null) {
                return vmPropertyValue;
            }
            String staticPropertyValue = staticProperties.getProperty(name);
            if (staticPropertyValue != null) {
                return MavenPropertyResolver.resolve(staticPropertyValue, domModel);
            }
            return null;
        };
    }

    private static String resolvePluginProperties(String value, MavenDomProjectModel domModel) {
        if (domModel != null) {
            value = MavenPropertyResolver.resolve(value, domModel);
        }
        return value;
    }

    private static String resolveVmProperties(ParametersList vmParameters, String value) {
        Matcher matcher = PROPERTY_PATTERN.matcher(value);
        Map<String, String> toReplace = new HashMap<>();
        while (matcher.find()) {
            String finding = matcher.group();
            String propertyValue = vmParameters.getPropertyValue(finding.substring(2, finding.length() - 1));
            if (propertyValue == null) {
                continue;
            }
            toReplace.put(finding, propertyValue);
        }
        for (Map.Entry<String, String> entry : toReplace.entrySet()) {
            value = value.replace(entry.getKey(), entry.getValue());
        }

        return value;
    }

    private static boolean isResolved(String s) {
        return !s.contains("${");
    }


}
