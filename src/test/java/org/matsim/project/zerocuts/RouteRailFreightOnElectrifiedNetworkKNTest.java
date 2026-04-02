package org.matsim.project.zerocuts;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.locationtech.jts.util.Assert;
import org.matsim.application.ApplicationUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.PopulationComparison;
import org.matsim.testcases.MatsimTestUtils;

import java.nio.file.Path;

class RouteRailFreightOnElectrifiedNetworkKNTest{
	private static final Logger log = LogManager.getLogger( RouteRailFreightOnElectrifiedNetworkKNTest.class );

	@RegisterExtension public MatsimTestUtils utils = new MatsimTestUtils() ;

	@Test public void test() {
		String outputDir = utils.getOutputDirectory(); // this method has a tendency to clean the outputDir so only call this once

		String [] args = {
			"--outputRun", outputDir,
			"--outputPlans", outputDir + "/plans" // the convention for this is a bit odd
		} ;
		new RouteRailFreightOnElectrifiedNetworkKN().execute(args);

		Path inputPlansFilename = ApplicationUtils.globFile( Path.of( utils.getInputDirectory() ), "*plans.xml.*" );
		Path outputPlansFilename = ApplicationUtils.globFile( Path.of( outputDir ), "*plans.xml.*" );

		PopulationComparison.Result result = PopulationUtils.comparePopulations( inputPlansFilename.toString(), outputPlansFilename.toString() );
		Assert.equals( result, PopulationComparison.Result.equal );

	}


}
