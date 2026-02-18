package org.matsim.prepare.longDistanceFreightGER;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.PopulationComparison;
import org.matsim.testcases.MatsimTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.matsim.core.population.routes.PopulationComparison.Result.equal;

public class GenerateFreightTripTest {

	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils() ;

	@Test
	public final void test() {

		// TODO: nur erste 10 Zeilen von ketten nehmen und dann 100% Sample
		assert Files.exists(Path.of(utils.getClassInputDirectory() + "german_freight.0.001pct.plans.xml.gz"));

		String [] args = {
			"--output", utils.getOutputDirectory(),
			"--sample", "0.001",
			"--land-use-filter"
		};

		GenerateFreightPlans.main( args );
		//		GenerateFreightPlans.main( null );
		// do this with null argument for the time being to really make sure that it uses the same setup as that main method (since that runs and this one here fails). kai, aug'25

		PopulationComparison.Result comparison = PopulationUtils.comparePopulations(utils.getClassInputDirectory() + "german_freight.0.001pct.plans.xml.gz", utils.getOutputDirectory() + "german_freight.0.001pct.plans.xml.gz");

		Assertions.assertEquals(equal, comparison);
	}
}
