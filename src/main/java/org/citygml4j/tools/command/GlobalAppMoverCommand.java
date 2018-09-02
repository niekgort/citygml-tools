package org.citygml4j.tools.command;

import org.citygml4j.builder.jaxb.CityGMLBuilderException;
import org.citygml4j.model.citygml.CityGML;
import org.citygml4j.model.citygml.CityGMLClass;
import org.citygml4j.model.citygml.appearance.Appearance;
import org.citygml4j.model.citygml.core.AbstractCityObject;
import org.citygml4j.model.module.citygml.CityGMLModuleType;
import org.citygml4j.model.module.citygml.CityGMLVersion;
import org.citygml4j.tools.appmover.GlobalAppMover;
import org.citygml4j.tools.appmover.LocalAppTarget;
import org.citygml4j.tools.appmover.ResultStatistic;
import org.citygml4j.tools.appmover.helper.GlobalAppReader;
import org.citygml4j.tools.common.log.Logger;
import org.citygml4j.tools.util.Util;
import org.citygml4j.xml.io.CityGMLInputFactory;
import org.citygml4j.xml.io.CityGMLOutputFactory;
import org.citygml4j.xml.io.reader.CityGMLReadException;
import org.citygml4j.xml.io.reader.CityGMLReader;
import org.citygml4j.xml.io.reader.FeatureReadMode;
import org.citygml4j.xml.io.reader.ParentInfo;
import org.citygml4j.xml.io.writer.CityGMLWriteException;
import org.citygml4j.xml.io.writer.CityModelInfo;
import org.citygml4j.xml.io.writer.CityModelWriter;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@CommandLine.Command(name = "move-global-apps",
        description = "Converts global appearances to local ones.",
        mixinStandardHelpOptions = true)
public class GlobalAppMoverCommand implements CityGMLTool {

    @CommandLine.Option(names = "--citygml", description = "CityGML version used for output file: 2.0, 1.0 (default: ${DEFAULT-VALUE}).")
    private String version = "2.0";

    @CommandLine.Option(names = "--feature", description = "Feature to assign the local appearance to: top-level, nested (default: ${DEFAULT-VALUE}).")
    private String target = "top-level";

    @CommandLine.Option(names = "--overwrite-files", description = "Overwrite input file(s).")
    private boolean overwriteInputFiles;

    @CommandLine.Parameters(arity = "1..*", paramLabel = "<files>", description = "File(s) to process (glob patterns allowed).")
    private List<String> files;

    @CommandLine.ParentCommand
    private MainCommand main;

    @Override
    public boolean execute() {
        Logger log = Logger.getInstance();
        log.info("Executing command 'move-global-apps'.");

        CityGMLInputFactory in;
        try {
            in = main.getCityGMLBuilder().createCityGMLInputFactory();
            in.setProperty(CityGMLInputFactory.FEATURE_READ_MODE, FeatureReadMode.SPLIT_PER_COLLECTION_MEMBER);
        } catch (CityGMLBuilderException e) {
            log.error("Failed to create CityGML input factory", e);
            return false;
        }

        CityGMLVersion targetVersion = version.equals("1.0") ? CityGMLVersion.v1_0_0 : CityGMLVersion.v2_0_0;
        CityGMLOutputFactory out = main.getCityGMLBuilder().createCityGMLOutputFactory(targetVersion);

        log.debug("Searching for CityGML input files.");
        List<Path> inputFiles = new ArrayList<>();
        for (String file : files) {
            try {
                inputFiles.addAll(Util.listFiles(file, false));
                log.debug("Found " + inputFiles.size() + " file(s) at '" + file + "'.");
            } catch (IOException e) {
                log.warn("Failed to find file(s) at '" + file + "'.");
            }
        }

        ResultStatistic resultStatistic = new ResultStatistic();
        for (int i = 0; i < inputFiles.size(); i++) {
            Path inputFile = inputFiles.get(i);
            log.info("[" + (i + 1) + "|" + inputFiles.size() + "] Processing file '" + inputFile.toAbsolutePath() + "'.");

            Path outputFile;
            if (!overwriteInputFiles) {
                outputFile = Util.addFileNameSuffix(inputFile, "-local-app");
                log.info("Writing output to file '" + outputFile.toAbsolutePath() + "'.");
            } else {
                outputFile = inputFile.resolveSibling("tmp-" + UUID.randomUUID());
                log.debug("Writing temporary output file '" + outputFile.toAbsolutePath() + "'.");
            }

            if (Files.exists(outputFile)) {
                log.error("The output file '" + outputFile.toAbsolutePath() + "' already exists. Remove it first.");
                continue;
            }

            GlobalAppMover appMover;
            try {
                log.debug("Reading global appearances from input file.");
                GlobalAppReader reader = new GlobalAppReader(main.getCityGMLBuilder());
                List<Appearance> appearances = reader.readGlobalApps(inputFile);

                if (appearances.size() == 0) {
                    log.info("The file does not contain global appearances. No action required.");
                    continue;
                }

                appMover = new GlobalAppMover(appearances);
                if (target.equalsIgnoreCase("nested"))
                    appMover.setLocalAppTarget(LocalAppTarget.NESTED_FEATURE);

                log.debug("Found " + appearances.size() + " global appearance(s).");
            } catch (CityGMLBuilderException | CityGMLReadException e) {
                log.error("Failed to read global appearances.", e);
                return false;
            }

            log.debug("Reading city objects from input file and moving global appearances.");

            try (CityGMLReader reader = in.createCityGMLReader(inputFile.toFile());
                 CityModelWriter writer = out.createCityModelWriter(outputFile.toFile())) {

                writer.setPrefixes(targetVersion);
                writer.setSchemaLocations(targetVersion);
                writer.setDefaultNamespace(targetVersion.getCityGMLModule(CityGMLModuleType.CORE));
                writer.setIndentString("  ");
                boolean isInitialized = false;

                while (reader.hasNext()) {
                    CityGML cityGML = reader.nextFeature();

                    // write city model
                    if (!isInitialized) {
                        ParentInfo parentInfo = reader.getParentInfo();
                        if (parentInfo != null && parentInfo.getCityGMLClass() == CityGMLClass.CITY_MODEL) {
                            CityModelInfo cityModelInfo = new CityModelInfo(parentInfo);
                            writer.setCityModelInfo(cityModelInfo);
                            writer.writeStartDocument();
                            isInitialized = true;
                        }
                    }

                    if (cityGML instanceof AbstractCityObject) {
                        AbstractCityObject cityObject = (AbstractCityObject) cityGML;
                        appMover.moveGlobalApps(cityObject);
                        writer.writeFeatureMember(cityObject);
                    }
                }

                if (appMover.hasRemainingGlobalApps()) {
                    List<Appearance> appearances = appMover.getRemainingGlobalApps();
                    log.info(appearances.size() + " global appearance(s) could not be moved due to implicit geometries.");
                    for (Appearance appearance : appearances)
                        writer.writeFeatureMember(appearance);
                } else
                    log.info("Successfully moved all global appearances.");

                log.debug("Processed city objects: " + appMover.getResultStatistic().getCityObjects());
                log.debug("Created local appearances: " + appMover.getResultStatistic().getAppearances());
                log.debug("Created ParameterizedTexture elements: " + appMover.getResultStatistic().getParameterizedTextures());
                log.debug("Created GeoreferencedTexture elements: " + appMover.getResultStatistic().getGeoreferencedTextures());
                log.debug("Created X3DMaterial elements: " + appMover.getResultStatistic().getX3DMaterials());

            } catch (CityGMLReadException e) {
                log.error("Failed to read city objects.", e);
                return false;
            } catch (CityGMLWriteException e) {
                log.error("Failed to write city objects.", e);
                return false;
            }

            if (overwriteInputFiles) {
                try {
                    log.debug("Replacing input file with temporary file.");
                    Files.delete(inputFile);
                    Files.move(outputFile, outputFile.resolveSibling(inputFile.getFileName()));
                } catch (IOException e) {
                    log.error("Failed to overwrite input file.", e);
                    return false;
                }
            }
        }

        return true;
    }

}
