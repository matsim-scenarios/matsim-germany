package org.matsim.prepare.longDistanceFreightGER;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.LanduseOptions;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.ProjectionUtils;
import picocli.CommandLine;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;

@CommandLine.Command(
        name = "generate-freight-plans",
        description = "Generate german wide freight population",
        showDefaultValues = true
)
public class GenerateFreightPlans implements MATSimAppCommand {
	// There are lots of hardcoded things in this whole package.  They should be sorted out and centralized.
	// In particular, the coordinate transforms are hardcoded, instead of beging centralized and/or taken from files.  In particular, they are not
	// taken from the shp files.

    private static final Logger log = LogManager.getLogger(GenerateFreightPlans.class);

    @CommandLine.Option(names = "--data", description = "Path to raw data (ketten 2010)",
            defaultValue = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/german-wide-freight/raw-data/ketten-2010.csv")
    private String dataPath;

    @CommandLine.Option(names = "--network", description = "Path to desired network file",
            defaultValue = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/germany-europe-network.xml.gz")
    private String networkPath;

    @CommandLine.Option(names = "--nuts", description = "Path to NUTS file (shape file)",
		defaultValue= "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/german-wide-freight/raw-data/shp/NUTS_RG_20M_2016_25832.shp/NUTS_RG_20M_2016_25832.shp")
    private String shpPath;

    @CommandLine.Option(names = "--output", description = "Output folder path", required = true)
    private Path output;

    @CommandLine.Option(names = "--truck-load", defaultValue = "13.0", description = "Average load of truck")
    private double averageTruckLoad;

    @CommandLine.Option(names = "--working-days", defaultValue = "260", description = "Number of working days in a year")
    private int workingDays;

    @CommandLine.Option(names = "--sample", defaultValue = "100", description = "Sample size of the freight plans (0, 100]")
    private double pct;

	@CommandLine.Option(names = "--land-use-filter", description = "specify land use type to filter out starting locations. Empty means no filter",
		arity = "0..*", split = ",", defaultValue = "industrial,commercial,retail")
	private Set<String> landUseTypes;

    @Override
    public Integer call() throws Exception {
		if (!Files.exists(output)) {
			Files.createDirectory(output);
		}

		// download land use shp (we need this because GeoTools cannot handle land use shp from URL properly)
		if (!landUseTypes.isEmpty()){
			downloadLanduseShp();
		}

        Network network = NetworkUtils.readNetwork(networkPath);
        log.info("Network successfully loaded!");

		String targetCRS = ProjectionUtils.getCRS( network );
		// yyyy There are several other places in the package where CRSes are manually set.

        log.info("preparing freight agent generator...");
//		LanduseOptions landuse = new LanduseOptions(output.toString() + "/landuse-shp/landuse.shp", Set.of("industrial", "commercial", "retail"));
		LanduseOptions landuse = null;
		if (!landUseTypes.isEmpty()){
			landuse = new LanduseOptions(output.toString() + "/landuse-shp/landuse.shp", landUseTypes);
		}
        FreightAgentGenerator freightAgentGenerator = new FreightAgentGenerator(network, shpPath, landuse, averageTruckLoad, workingDays, pct / 100);
        log.info("Freight agent generator successfully created!");

        log.info("Reading trip relations...");
        List<TripRelation> tripRelations = TripRelation.readTripRelations(dataPath);
		log.info("Trip relations successfully loaded. There are {} trip relations", tripRelations.size());

        log.info("Start generating population...");
        Population outputPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig());

		ProjectionUtils.putCRS( outputPopulation, targetCRS );

        for (int i = 0; i < tripRelations.size(); i++) {
            List<Person> persons = freightAgentGenerator.generateFreightAgents(tripRelations.get(i), Integer.toString(i));
            for (Person person : persons) {
                outputPopulation.addPerson(person);
            }

            if (i % 500000 == 0) {
				log.info("Processing: {} out of {} entries have been processed", i, tripRelations.size());
            }
        }
        String outputPlansPath = output.toString() + "/german_freight." + pct + "pct.plans.xml.gz";
        PopulationWriter populationWriter = new PopulationWriter(outputPopulation);
        populationWriter.write(outputPlansPath);

        // Write down tsv file for visualisation and analysis
        String freightTripTsvPath = output.toString() + "/freight_trips_data.tsv";

		createOutput_tripOD_relations(freightTripTsvPath, outputPopulation);

		return 0;
    }

	public static void createOutput_tripOD_relations(String freightTripTsvPath, Population outputPopulation) throws IOException {
		CSVPrinter tsvWriter = new CSVPrinter(new FileWriter(freightTripTsvPath), CSVFormat.TDF);
		tsvWriter.printRecord("trip_id", "from_x", "from_y", "to_x", "to_y");
		for (Person person : outputPopulation.getPersons().values()) {
			List<PlanElement> planElements = person.getSelectedPlan().getPlanElements();
			Activity act0 = (Activity) planElements.get(0);
			Activity act1 = (Activity) planElements.get(2);
			Coord fromCoord = act0.getCoord();
			Coord toCoord = act1.getCoord();
			tsvWriter.printRecord(person.getId().toString(), fromCoord.getX(), fromCoord.getY(), toCoord.getX(), toCoord.getY());
		}
		tsvWriter.close();
	}

	public static void main(String[] args) {
		if ( args==null || args.length==0 ) {
			args = new String[] {
					"--output", "output-longDistanceFreightGER"
					,"--sample", "0.1"
					,"--land-use-filter" // only for testing!
			};
		}
        new GenerateFreightPlans().execute(args);
    }

	private void downloadLanduseShp() throws IOException {
		Path targetDir = output.resolve("landuse-shp");
		if (Files.exists(targetDir)){
			log.info("Land use shp folder already existed. It will not be downloaded again.");
			return;
		}
		Files.createDirectories(targetDir);

		// download shp from svn
		String baseUrl = "https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/german-wide-freight/raw-data/shp/landuse/";
		List<String> fileNames = List.of(
			"landuse.shp",
			"landuse.dbf",
			"landuse.shx",
			"landuse.prj",
			"landuse.fix"
		);
		for (String fileName : fileNames) {
			String fileUrl = baseUrl + fileName;
			Path outputPath = targetDir.resolve(fileName);
			System.out.println("Downloading " + fileName + "...");

			try (InputStream in = URI.create(fileUrl).toURL().openStream()) {
				Files.copy(in, outputPath, StandardCopyOption.REPLACE_EXISTING);
			}

			System.out.println("Saved to " + outputPath);
		}
	}
}
