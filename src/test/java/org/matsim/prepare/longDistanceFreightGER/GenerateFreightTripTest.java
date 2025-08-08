package org.matsim.prepare.longDistanceFreightGER;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.core.population.PopulationUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

public class GenerateFreightTripTest {

	private static final Logger log = LogManager.getLogger(GenerateFreightTripTest.class);

	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils() ;

	@Test
	public final void test() {
		assert Files.exists(Path.of(utils.getClassInputDirectory() + "german_freight.0.001pct.plans.xml.gz"));

		String [] args = {
			"--output", utils.getOutputDirectory(),
			"--sample", "0.001",
			"--land-use-filter"
		};

		GenerateFreightPlans.main( args );
		//		GenerateFreightPlans.main( null );
		// do this with null argument for the time being to really make sure that it uses the same setup as that main method (since that runs and this one here fails). kai, aug'25

		PopulationUtils.comparePopulations(utils.getClassInputDirectory() + "german_freight.0.001pct.plans.xml.gz", utils.getOutputDirectory() + "german_freight.0.001pct.plans.xml.gz");
	}
}
