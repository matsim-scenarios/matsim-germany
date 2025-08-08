package org.matsim.prepare.longDistanceFreightGER;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.util.factory.Hints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.core.population.PopulationUtils;
import org.matsim.testcases.MatsimTestUtils;

import static org.matsim.prepare.longDistanceFreightGER.GenerateFreightPlans.createOutput_tripOD_relations;

public class GenerateFreightTripTest {

	private static final Logger log = LogManager.getLogger(GenerateFreightTripTest.class);

	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils() ;

	// TODO the test fails... But the main class with the same input arguments can run without problem... I don't know why
	@Test
	public final void test() {
		String [] args = {
			"--output", utils.getOutputDirectory(),
			"--sample", "0.001",
			"--land-use-filter"
		};

//		Hints.putSystemDefault(Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER, Boolean.TRUE );

		GenerateFreightPlans.main( null );
		// do this with null argument for the time being to really make sure that it uses the same setup as that main method (since that runs and this one here fails). kai, aug'25

		PopulationUtils.comparePopulations(utils.getInputDirectory() + "german_freight.0.001pct.plans.xml.gz", utils.getOutputDirectory() + "german_freight.0.001pct.plans.xml.gz");
	}

//	@Test
//	public final void test2() throws IOException {
//		Network network = NetworkUtils.readNetwork("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/german-wide-freight/v2/germany-europe-network.xml.gz");
//		FreightAgentGenerator freightAgentGenerator = new FreightAgentGenerator(network,
//			"https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/german-wide-freight/raw-data/shp/NUTS_RG_20M_2016_4326.shp/NUTS_RG_20M_2016_4326.shp",
//			null, 13, 260, 0.001 / 100);
//		log.info("Freight agent generator successfully created!");
//
//		log.info("Reading trip relations...");
//		List<TripRelation> tripRelations = TripRelation.readTripRelations("https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/german-wide-freight/raw-data/ketten-2010.csv" );
//		log.info("Trip relations successfully loaded. There are {} trip relations", tripRelations.size());
//
//		log.info("Start generating population...");
//		Population outputPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig());
//		for (int i = 0; i < tripRelations.size(); i++) {
//			List<Person> persons = freightAgentGenerator.generateFreightAgents(tripRelations.get(i), Integer.toString(i));
//			for (Person person : persons) {
//				outputPopulation.addPerson(person);
//			}
//		}
//
//		String outputPlansPath = utils.getOutputDirectory() + "/german_freight." + 0.001 + "pct.plans.xml.gz";
//		PopulationWriter populationWriter = new PopulationWriter(outputPopulation);
//		populationWriter.write(outputPlansPath);
//
//		// Write down tsv file for visualisation and analysis
//		String freightTripTsvPath = utils.getOutputDirectory() + "/freight_trips_data.tsv";
//		createOutput_tripOD_relations(freightTripTsvPath, outputPopulation);
//	}



}
