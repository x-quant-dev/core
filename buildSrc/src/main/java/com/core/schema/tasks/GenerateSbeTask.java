package com.core.schema.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/**
 * Gradle task to generate SBE codec classes from an SBE XML schema.
 */
public class GenerateSbeTask extends DefaultTask {

    private String sbeXml;
    private String outputDir;

    /**
     * Get the SBE schema XML file.
     * @return the SBE schema XML file
     */
    @InputFile
    public String getSbeXml() {
        return this.sbeXml;
    }

    /**
     * Set the SBE schema XML file.
     * @param sbeXml the SBE schema XML file
     */
    public void setSbeXml(String sbeXml) {
        this.sbeXml = sbeXml;
    }

    /**
     * Get the output directory for generated SBE codecs.
     * @return the output directory
     */
    @OutputDirectory
    public String getOutputDir() { return this.outputDir; }

    /**
     * Set the output directory for generated SBE codecs.
     * @param outputDir the output directory
     */
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    /**
     * Run the SBE code generation.
     */
    @TaskAction
    public void generate() throws Exception {
        System.setProperty("sbe.output.dir", getOutputDir());
        System.setProperty("sbe.target.language", "Java");
        System.setProperty("sbe.validation.stop.on.error", "true");
        uk.co.real_logic.sbe.SbeTool.main(new String[] { getSbeXml() });
    }
}
